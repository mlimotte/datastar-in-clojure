(ns brewboard.routes
  "The route table and handlers. Pages are GETs that return whole HTML pages.
  Streams are held-open GETs fed by the hub. Commands change state and return 204: the
  stream carries the new view, including any error, which is per-tab server state.
  Only /draft answers 200, to fix size/milk signals the new drink makes invalid."
  (:require [brewboard.hub :as hub]
            [brewboard.menu :as menu]
            [brewboard.native :as native]
            [brewboard.sse :as sse]
            [brewboard.state :as state]
            [brewboard.views :as views]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [org.httpkit.server :as hk]
            [reitit.ring :as ring]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]]))

;; ---------------------------------------------------------------- middleware

(defn wrap-sid
  "Give each browser a `sid` cookie; put it on the request as :sid."
  [handler]
  (fn [req]
    (let [sid     (get-in req [:cookies "sid" :value])
          new-sid (when-not sid (str (random-uuid)))]
      (cond-> (handler (assoc req :sid (or sid new-sid)))
        new-sid (assoc-in [:cookies "sid"] {:value new-sid :path "/" :http-only true :same-site :lax})))))

(defn wrap-signals
  "Read Datastar's signals once (a POST body can be read only once); put them on :signals."
  [handler]
  (fn [req] (handler (assoc req :signals (sse/read-signals req)))))

(defn wrap-datastar-request
  "Commands must carry `Datastar-Request: true`. A cross-origin page cannot add that header
  without a CORS preflight, so this is a cheap (partial) CSRF guard."
  [handler]
  (fn [req]
    (if (= "true" (get-in req [:headers "datastar-request"]))
      (handler req)
      {:status 403 :headers {"Content-Type" "text/plain"} :body "Datastar requests only"})))

;; ---------------------------------------------------------------- helpers

(defn- html-response [body]
  {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body body})

(defn- ->kw [s] (some-> s not-empty keyword))

(defn draft->line
  "The add-to-cart line from the draft signals (strings from the browser)."
  [{:keys [drink size milk qty]}]
  {:drink (->kw drink) :size (->kw size) :milk (->kw milk) :qty qty})

(defn fit-draft
  "Keep the size and milk when they are valid for `drink`; else take the first valid ones
  (a sold-out milk is not valid). A drink with no milks gets milk nil."
  [drink {:keys [size milk]} sold-out]
  (let [{:keys [sizes milks]} (menu/by-id drink)]
    {:drink drink
     :size  (if (some #{size} sizes) size (first sizes))
     :milk  (if (and (some #{milk} milks) (not (contains? sold-out milk)))
              milk
              (menu/first-valid milks sold-out))}))

(defn signal-fixes
  "The draft signals that must change so the browser matches `fitted`. Milk goes out as \"\",
  not null: null would delete the signal."
  ([draft fitted] (signal-fixes draft fitted [:size :milk]))
  ([draft fitted ks]
   (into {} (for [k ks :when (not= (k draft) (k fitted))]
              [k (or (some-> (k fitted) name) "")]))))

(defn adopt-draft
  "A tab (re)opening its stream brings its draft in its signals. Fit it to the menu and the
  stock; return {:draft fitted :fixes signals-to-patch}. This is how a reconnect re-syncs
  server and browser."
  [state signals]
  (let [{:keys [drink] :as draft} (draft->line signals)
        fitted (if (menu/by-id drink)
                 (fit-draft drink draft (:sold-out state))
                 (views/default-draft state))]
    {:draft fitted :fixes (signal-fixes draft fitted [:drink :size :milk])}))

(defn- stream
  "Hold the connection open and register it with the hub. `open!` runs before the first
  render and may return extra events to send after it; `close!` runs on disconnect."
  [{:keys [hub !state]} req sub & {:keys [open! close!]}]
  (let [id (random-uuid)]
    (hk/as-channel req
                   {:on-open  (fn [ch]
                                (hk/send! ch {:status 200 :headers (assoc sse/stream-headers "Connection" "keep-alive")} false)
                                (let [extra (when open! (open!))]
                                  (hub/subscribe! hub id (assoc sub :send-fn #(hk/send! ch % false)) !state)
                                  (doseq [e extra] (hk/send! ch e false))))
                    :on-close (fn [_ _]
                                (hub/unsubscribe! hub id)
                                (when close! (close!)))})))

(defn- customer-stream
  "One stream per tab. Opening it registers the tab and adopts its draft; closing it
  forgets the tab (the next open adopts the draft again, so nothing is lost)."
  [{:keys [!state] :as sys} {:keys [sid signals] :as req}]
  (let [tabid     (or (not-empty (:tabid signals)) (str (random-uuid)))
        stream-id (random-uuid)]
    (stream sys req {:view-fn  #(views/customer-app % sid tabid)
                     :extra-fn #(native/ready-events sid %1 %2)}
            :open!  (fn []
                      (let [{:keys [draft fixes]} (adopt-draft @!state signals)]
                        (state/command! !state {:type :open-tab :sid sid :tabid tabid
                                                :stream stream-id :draft draft})
                        (when (seq fixes) [(sse/patch-signals fixes)])))
            :close! #(state/command! !state {:type :close-tab :tabid tabid :stream stream-id}))))

;; ---------------------------------------------------------------- routes

(defn routes [{:keys [!state] :as sys}]
  (let [run      (fn [{:keys [signals]} cmd]
                   (state/command! !state (assoc cmd :tabid (:tabid signals)))
                   sse/no-content)
        shell?   native/shell-request?
        commands {:middleware [wrap-datastar-request]}]
    [["/" {:get (fn [{:keys [sid] :as req}] (html-response (views/customer-page @!state sid (shell? req))))}]
     ["/board" {:get (fn [req] (html-response (views/board-page @!state (shell? req))))}]
     ["/settings" {:get (fn [{:keys [sid] :as req}] (html-response (views/settings-page @!state sid (shell? req))))}]
     ["/brewboard.css" {:get (fn [_] {:status 200 :headers {"Content-Type" "text/css"}
                                      :body (slurp (io/resource "brewboard/brewboard.css"))})}]

     ["/customer/stream" {:get #(customer-stream sys %)}]
     ["/board/stream" {:get (fn [req] (stream sys req {:view-fn views/board-app}))}]

     ;; Picking a drink is a command: the server stores this tab's fitted draft and the
     ;; stream's re-render carries the new option lists and price. The response fixes only
     ;; the signals.
     ["/draft" (assoc commands :post (fn [{:keys [signals] :as req}]
                                       (let [{:keys [drink] :as draft} (draft->line signals)]
                                         (if (menu/by-id drink)
                                           (let [fitted (fit-draft drink draft (:sold-out @!state))
                                                 fixes  (signal-fixes draft fitted)]
                                             (run req {:type :choose-draft :draft fitted})
                                             (if (seq fixes) (sse/sse-response (sse/patch-signals fixes)) sse/no-content))
                                           sse/no-content))))]

     ["/cart/items" (assoc commands :post (fn [{:keys [sid signals] :as req}]
                                            (run req {:type :add-to-cart :sid sid :line (draft->line signals)})))]
     ["/cart/items/:idx" (assoc commands :delete (fn [{:keys [sid path-params] :as req}]
                                                   (run req {:type :remove-from-cart :sid sid
                                                             :idx (parse-long (:idx path-params))})))]
     ["/orders" (assoc commands :post (fn [{:keys [sid signals] :as req}]
                                        (run req {:type :place-order :sid sid :customer (:customer signals)})))]
     ["/orders/:id" {:get (fn [{:keys [sid path-params] :as req}]
                            (html-response (views/order-page @!state sid (parse-long (:id path-params)) (shell? req))))}]
     ["/orders/:id/stream" {:get (fn [{:keys [sid path-params] :as req}]
                                   (let [id (parse-long (:id path-params))]
                                     (stream sys req {:view-fn #(views/order-app % sid id)
                                                      :extra-fn #(native/ready-events sid %1 %2)})))}]
     ["/orders/:id/advance" (assoc commands :post (fn [{:keys [path-params] :as req}]
                                                    (run req {:type :advance :id (parse-long (:id path-params))})))]
     ["/stock/:milk/toggle" (assoc commands :post (fn [{:keys [path-params] :as req}]
                                                    (run req {:type :toggle-stock :milk (keyword (:milk path-params))})))]

     ["/native/path-config" {:get (fn [_] {:status 200 :headers {"Content-Type" "application/json"}
                                           :body (json/generate-string native/path-config)})}]
     ["/native/device" (assoc commands :post (fn [{:keys [sid signals] :as req}]
                                               (run req {:type :register-device :sid sid :token (:pushToken signals)})))]]))

(defn app
  "The Ring handler for a system {:!state atom :hub hub}."
  [sys]
  (-> (ring/ring-handler (ring/router (routes sys)) (ring/create-default-handler))
      wrap-signals
      wrap-sid
      wrap-cookies
      wrap-params))
