(ns brewboard.routes
  "The route table and handlers. Pages are GETs that return whole HTML pages.
  Streams are held-open GETs fed by the hub. Commands change state and return 204
  (the stream carries the new view) or a 200 one-shot SSE response that patches signals
  (an error message, or draft fixes). Datastar patches nothing from a 4xx."
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

(defn- command-response
  "204 when the command ran (the stream will show it), else the error as a signal."
  [error]
  (if error
    (sse/sse-response (sse/patch-signals {:_error error}))
    sse/no-content))

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
  "The size/milk signals that must change after fitting the draft. Milk goes out as \"\",
  not null: null would delete the signal."
  [draft fitted]
  (into {} (for [k [:size :milk] :when (not= (k draft) (k fitted))]
             [k (or (some-> (k fitted) name) "")])))

(defn- stream
  "Hold the connection open and register it with the hub."
  [{:keys [hub !state]} req sub]
  (let [id (random-uuid)]
    (hk/as-channel req
                   {:on-open  (fn [ch]
                                (hk/send! ch {:status 200 :headers (assoc sse/stream-headers "Connection" "keep-alive")} false)
                                (hub/subscribe! hub id (assoc sub :send-fn #(hk/send! ch % false)) @!state))
                    :on-close (fn [_ _] (hub/unsubscribe! hub id))})))

;; ---------------------------------------------------------------- routes

(defn routes [{:keys [!state] :as sys}]
  (let [run      #(command-response (state/command! !state %))
        shell?   native/shell-request?
        commands {:middleware [wrap-datastar-request]}]
    [["/" {:get (fn [{:keys [sid] :as req}] (html-response (views/customer-page @!state sid (shell? req))))}]
     ["/board" {:get (fn [req] (html-response (views/board-page @!state (shell? req))))}]
     ["/settings" {:get (fn [{:keys [sid] :as req}] (html-response (views/settings-page @!state sid (shell? req))))}]
     ["/brewboard.css" {:get (fn [_] {:status 200 :headers {"Content-Type" "text/css"}
                                      :body (slurp (io/resource "brewboard/brewboard.css"))})}]

     ["/customer/stream" {:get (fn [{:keys [sid] :as req}]
                                 (stream sys req {:view-fn  #(views/customer-app % sid)
                                                  :extra-fn #(native/ready-events sid %1 %2)}))}]
     ["/board/stream" {:get (fn [req] (stream sys req {:view-fn views/board-app}))}]

     ;; Picking a drink is a command: the server stores the fitted draft and the stream's
     ;; re-render carries the new option lists. The response fixes only the signals.
     ["/draft" (assoc commands :post (fn [{:keys [sid] :as req}]
                                       (let [{:keys [drink] :as draft} (draft->line (sse/read-signals req))]
                                         (if (menu/by-id drink)
                                           (let [fitted (fit-draft drink draft (:sold-out @!state))
                                                 fixes  (signal-fixes draft fitted)]
                                             (state/command! !state {:type :choose-draft :sid sid :draft fitted})
                                             (if (seq fixes) (sse/sse-response (sse/patch-signals fixes)) sse/no-content))
                                           sse/no-content))))]

     ["/cart/items" (assoc commands :post (fn [{:keys [sid] :as req}]
                                            (run {:type :add-to-cart :sid sid
                                                  :line (draft->line (sse/read-signals req))})))]
     ["/cart/items/:idx" (assoc commands :delete (fn [{:keys [sid path-params]}]
                                                   (run {:type :remove-from-cart :sid sid
                                                         :idx (parse-long (:idx path-params))})))]
     ["/orders" (assoc commands :post (fn [{:keys [sid] :as req}]
                                        (run {:type :place-order :sid sid
                                              :customer (:customer (sse/read-signals req))})))]
     ["/orders/:id" {:get (fn [{:keys [sid path-params] :as req}]
                            (html-response (views/order-page @!state sid (parse-long (:id path-params)) (shell? req))))}]
     ["/orders/:id/stream" {:get (fn [{:keys [sid path-params] :as req}]
                                   (let [id (parse-long (:id path-params))]
                                     (stream sys req {:view-fn #(views/order-app % sid id)
                                                      :extra-fn #(native/ready-events sid %1 %2)})))}]
     ["/orders/:id/advance" (assoc commands :post (fn [{:keys [path-params]}]
                                                    (run {:type :advance :id (parse-long (:id path-params))})))]
     ["/stock/:milk/toggle" (assoc commands :post (fn [{:keys [path-params]}]
                                                    (run {:type :toggle-stock :milk (keyword (:milk path-params))})))]

     ["/native/path-config" {:get (fn [_] {:status 200 :headers {"Content-Type" "application/json"}
                                           :body (json/generate-string native/path-config)})}]
     ["/native/device" (assoc commands :post (fn [{:keys [sid] :as req}]
                                               (run {:type :register-device :sid sid
                                                     :token (:pushToken (sse/read-signals req))})))]]))

(defn app
  "The Ring handler for a system {:!state atom :hub hub}."
  [sys]
  (-> (ring/ring-handler (ring/router (routes sys)) (ring/create-default-handler))
      wrap-sid
      wrap-cookies
      wrap-params))
