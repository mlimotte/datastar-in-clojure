(ns lesson06.live
  "Part 6: one stream, many viewers. Each page opens one long-lived GET stream. Commands change
  the state atom and answer 204. A watch on the atom re-renders the whole #app view for every open
  stream and sends it as one patch; Datastar's morph applies only the difference. A viewer whose
  view did not change is not sent anything."
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [hiccup2.core :as h]
            [lesson06.sse :as sse]
            [org.httpkit.server :as hk]
            [reitit.ring :as ring]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]])
  (:import (java.net CookieManager URI URLEncoder)
           (java.net.http HttpClient HttpClient$Version HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)))

;; ---------------------------------------------------------------------------
;; Menu and prices (copied from part 3)

(def menu
  [{:id :espresso   :name "Espresso"   :base 300 :sizes [:single :double]        :milks []}
   {:id :americano  :name "Americano"  :base 350 :sizes [:small :large]          :milks []}
   {:id :latte      :name "Latte"      :base 450 :sizes [:small :medium :large]  :milks [:whole :oat :almond]}
   {:id :cappuccino :name "Cappuccino" :base 425 :sizes [:small :medium]         :milks [:whole :oat]}
   {:id :mocha      :name "Mocha"      :base 500 :sizes [:small :medium :large]  :milks [:whole :oat :almond]}
   {:id :chai       :name "Chai Latte" :base 450 :sizes [:medium :large]         :milks [:whole :oat :almond]}])

(def drinks (into {} (map (juxt :id identity)) menu))
(def size-delta {:single 0 :double 100 :small 0 :medium 50 :large 100})
(def milk-delta {:whole 0 :oat 60 :almond 60})

(defn price-cents
  "Price of one drink: base plus size and milk deltas."
  [{:keys [drink size milk]}]
  (+ (get-in drinks [drink :base]) (size-delta size) (milk-delta milk 0)))

(defn line-total [{:keys [qty] :as line}] (* qty (price-cents line)))

(defn dollars [cents] (format "$%d.%02d" (quot cents 100) (rem cents 100)))

(defn label [k] (some-> k name string/capitalize))

;; ---------------------------------------------------------------------------
;; State and commands (copied from part 5)

(def initial-state {:sold-out #{} :carts {} :orders (sorted-map) :next-id 1 :drafts {}})

(defonce !state (atom initial-state))

(def next-status {:placed :making :making :ready :ready :picked-up})

(defn line-error
  "Why a draft line can't go in a cart, or nil when it can."
  [{:keys [sold-out]} {:keys [drink size milk qty]}]
  (let [{:keys [sizes milks] :as d} (drinks drink)]
    (cond
      (nil? d)                                     "Pick a drink."
      (not (some #{size} sizes))                   "Pick a size this drink comes in."
      (and (seq milks) (not (some #{milk} milks))) "Pick a milk."
      (and (empty? milks) milk)                    "This drink takes no milk."
      (sold-out milk)                              (str (label milk) " milk is sold out.")
      (not (pos-int? qty))                         "Quantity must be at least 1.")))

(defn fit-draft
  "The draft with size and milk replaced by the drink's first valid choice when they don't fit it.
  A sold-out milk doesn't fit."
  [{:keys [sold-out]} {:keys [drink size milk]}]
  (let [{:keys [id sizes milks]} (or (drinks drink) (drinks :latte))
        valid-milks (remove sold-out milks)]
    {:drink id
     :size  (if (some #{size} sizes) size (first sizes))
     :milk  (if (some #{milk} valid-milks) milk (first valid-milks))}))

(defn handle-command
  "Apply one command to the state. Pure: returns the new state."
  [state {:keys [type sid line idx customer id milk draft]}]
  (case type
    :add-to-cart      (cond-> state
                        (nil? (line-error state line)) (update-in [:carts sid] (fnil conj []) line))
    :remove-from-cart (update-in state [:carts sid]
                                 #(into [] (keep-indexed (fn [i l] (when (not= i idx) l))) %))
    :place-order      (let [lines (get-in state [:carts sid])]
                        (if (or (empty? lines) (string/blank? customer))
                          state
                          (-> state
                              (assoc-in [:orders (:next-id state)]
                                        {:id (:next-id state) :sid sid :customer (string/trim customer)
                                         :status :placed :lines lines})
                              (update :next-id inc)
                              (update :carts dissoc sid))))
    :advance          (if-let [status (get-in state [:orders id :status])]
                        (if (= :picked-up (next-status status))
                          (update state :orders dissoc id)
                          (assoc-in state [:orders id :status] (next-status status)))
                        state)
    :toggle-stock     (update state :sold-out #(if (% milk) (disj % milk) (conj % milk)))
    ;; New in part 6: the draft a browser is configuring shapes its view, and the server renders
    ;; the whole view, so the draft moves to the server and choosing a drink becomes a command.
    :choose-draft     (assoc-in state [:drafts sid] (fit-draft state draft))
    state))

;; ---------------------------------------------------------------------------
;; Views. Each viewer gets one whole view: <main id="app">. No region ids to keep in sync with
;; commands any more; the morph finds what changed.

(def datastar-src
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.4/bundles/datastar.js")

(defn html [hiccup] (str (h/html hiccup)))

(defn drink-options-view
  "The size and milk drop-downs for one drink. The option lists are always server HTML."
  [drink sold-out]
  (let [{:keys [sizes milks]} (drinks drink)]
    [:div#drink-options
     [:select#size {:data-bind:size ""}
      (for [s sizes] [:option {:value (name s)} (str (label s) " +" (dollars (size-delta s)))])]
     (if (seq milks)
       [:select#milk {:data-bind:milk ""}
        (for [m milks]
          (if (sold-out m)
            [:option {:value (name m) :disabled ""} (str (label m) " (sold out)")]
            [:option {:value (name m)} (label m)]))]
       [:p.muted "No milk"])]))

(defn- line-text [{:keys [drink size milk qty] :as line}]
  (str qty " × " (label size) " " (when milk (str (label milk) " ")) (get-in drinks [drink :name])
       " — " (dollars (line-total line))))

(defn- customer-view [state sid]
  (let [lines (get-in state [:carts sid])]
    [:main#app
     [:h1 "Brew Board"]
     [:section#draft
      [:select#drink {:data-bind:drink "" :data-on:change "@post('/draft')"}
       (for [{:keys [id name]} menu] [:option {:value (clojure.core/name id)} name])]
      (drink-options-view (get-in state [:drafts sid :drink] :latte) (:sold-out state))
      [:input {:type "number" :min "1" :data-bind:qty ""}]
      [:button#add-to-cart {:data-on:click "@post('/cart/items')"} "Add to cart"]
      [:p.error {:data-text "$_error"}]]
     [:section#cart
      [:h2 "Cart"]
      (if (empty? lines)
        [:p.muted "Empty."]
        [:ul (map-indexed (fn [i line]
                            [:li (line-text line) " "
                             [:button {:data-on:click (str "@delete('/cart/items/" i "')")} "remove"]])
                          lines)])
      [:p "Total: " (dollars (reduce + (map line-total lines)))]
      [:input#customer {:data-bind:customer "" :placeholder "Your name"}]
      [:button#place-order {:data-on:click "@post('/orders')"} "Place order"]]
     [:section#my-orders
      [:h2 "Your orders"]
      [:ul (for [{:keys [id customer status] :as o} (vals (:orders state)) :when (= sid (:sid o))]
             [:li {:id (str "order-" id)} (str "#" id " for " customer ": " (name status))])]]]))

(defn- board-view [state]
  [:main#app
   [:h1 "Barista board"]
   [:section#board-orders
    [:h2 "Orders"]
    [:ul (for [{:keys [id customer status lines]} (vals (:orders state))]
           [:li {:id (str "order-" id)}
            (str "#" id " " customer " (" (name status) "): "
                 (string/join ", " (map #(str (:qty %) "× " (get-in drinks [(:drink %) :name])) lines)))
            " " [:button {:data-on:click (str "@post('/orders/" id "/advance')")} "advance"]])]]
   [:section#stock
    [:h2 "Milk"]
    (for [m [:whole :oat :almond]]
      [:button {:id (str "stock-" (name m)) :data-on:click (str "@post('/stock/" (name m) "/toggle')")}
       (str (label m) (when ((:sold-out state) m) " (sold out)"))])]])

(defn view-for
  "The whole #app view one viewer sees. A viewer is {:kind :customer :sid …} or {:kind :board}."
  [state {:keys [kind sid]}]
  ;; EXERCISE(view-for): Return the hiccup for <main id="app"> for this viewer: a customer sees
  ;; the draft form, their own cart and only their own orders; the board sees every order and
  ;; the milk stock. (customer-view and board-view are already written.)
  (case kind
    :customer (customer-view state sid)
    :board    (board-view state))
  ;; END EXERCISE
  )

(defn page
  "A full page: Datastar, the draft signals, an element that opens the stream, and a first render.
  data-signals sits on <body>, outside #app, so re-rendering #app never touches it.
  retry 'always': with the default 'auto' a stream the server ends cleanly is not reopened.
  (A GET also closes while the tab is hidden and reopens when it shows; on-open then sends the
  whole view again.)"
  [title signals stream-url view]
  (str "<!DOCTYPE html>"
       (h/html
        [:html {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title title]
          [:script {:type "module" :src datastar-src}]]
         [:body (when signals {:data-signals (json/generate-string signals)})
          [:div {:data-init (str "@get('" stream-url "', {retry: 'always'})")}]
          view]])))

;; ---------------------------------------------------------------------------
;; The hub: who is watching, and what each was last sent. It knows nothing about http-kit:
;; a subscriber is a send-fn (a function of one string) and a viewer.

(defn make-hub [] (atom {}))

(defn subscribe!
  "Register a stream. :last is nil, so the next broadcast sends it the whole view."
  [hub id send-fn viewer]
  (swap! hub assoc id {:send-fn send-fn :viewer viewer :last nil}))

(defn unsubscribe! [hub id] (swap! hub dissoc id))

(defn broadcast!
  "Render each subscriber's view of state. Send it as one patch-elements event only when the HTML
  differs from what that subscriber was last sent. Returns the ids that were sent to."
  [hub state]
  ;; EXERCISE(broadcast): For every subscriber in the hub, render (view-for state viewer) to an
  ;; HTML string. If it differs from the subscriber's :last, call its send-fn with a
  ;; patch-elements event of that HTML and record it as :last. Return the ids that were sent to.
  (locking hub
    (into []
          (keep (fn [[id {:keys [send-fn viewer last]}]]
                  (let [view (html (view-for state viewer))]
                    (when (not= view last)
                      (send-fn (sse/patch-elements view))
                      (swap! hub assoc-in [id :last] view)
                      id))))
          @hub))
  ;; END EXERCISE
  )

(defn stream-lifecycle
  "The open and close callbacks for one stream, independent of the web server.
  {:on-open (fn [send-fn]) :on-close (fn [])}"
  [hub !state viewer]
  ;; EXERCISE(stream-lifecycle): Make a fresh id for this stream. On open, subscribe send-fn with
  ;; the viewer and send it the whole current view right away (a reconnect must get the view
  ;; again too). On close, unsubscribe it.
  (let [id (random-uuid)]
    {:on-open  (fn [send-fn]
                 (subscribe! hub id send-fn viewer)
                 (broadcast! hub @!state))
     :on-close (fn [] (unsubscribe! hub id))})
  ;; END EXERCISE
  )

(defn stream-handler
  "Hold a GET open with http-kit: send headers once, then every patch the hub sends."
  [hub !state viewer]
  (fn [req]
    (let [{:keys [on-open on-close]} (stream-lifecycle hub !state viewer)]
      (hk/as-channel req
                     {:on-open  (fn [ch]
                                  (hk/send! ch {:status 200
                                                :headers (assoc sse/sse-headers "Connection" "keep-alive")}
                                            false)
                                  (on-open #(hk/send! ch % false)))
                      :on-close (fn [_ch _status] (on-close))}))))

;; ---------------------------------------------------------------------------
;; Routes. Commands answer 204: the stream carries the result to every viewer, the sender
;; included. The only thing a command answers itself is feedback for that one request.

(defn wrap-datastar-request
  "403 for a command without `Datastar-Request: true`."
  [handler]
  (fn [req]
    (if (= "true" (get-in req [:headers "datastar-request"]))
      (handler req)
      {:status 403 :headers {"Content-Type" "text/plain"} :body "Datastar requests only"})))

(defn wrap-sid
  "Give every browser a `sid` cookie (toy: unsigned) and put it on the request as :sid."
  [handler]
  (fn [req]
    (if-let [sid (get-in req [:cookies "sid" :value])]
      (handler (assoc req :sid sid))
      (let [sid (str (random-uuid))]
        (assoc-in (handler (assoc req :sid sid)) [:cookies "sid"] {:value sid :path "/" :same-site :lax})))))

(defn signal->keyword [s] (when-not (string/blank? (str s)) (keyword s)))

(defn draft-line [{:keys [drink size milk qty]}]
  {:drink (signal->keyword drink) :size (signal->keyword size) :milk (signal->keyword milk)
   :qty   (cond-> qty (string? qty) parse-long)})

(def no-content {:status 204})

(def draft-signals {:drink "latte" :size "small" :milk "whole" :qty 1 :customer "" :_error ""})

(defn choose-draft
  "Command: store this browser's draft, fitted to the drink, so the stream re-renders the
  drop-downs from it. Answer with the size/milk signals that no longer fit, or 204."
  [!state {:keys [sid] :as req}]
  (let [draft (-> (sse/read-signals req) (select-keys [:drink :size :milk]) (update-vals signal->keyword))
        fitted (get-in (swap! !state handle-command {:type :choose-draft :sid sid :draft draft}) [:drafts sid])
        fix (into {} (for [k [:size :milk] :when (not= (k draft) (k fitted))]
                       [k (or (some-> (k fitted) name) "")]))]
    (if (seq fix) (sse/sse-response (sse/patch-signals fix)) no-content)))

(defn add-item [!state {:keys [sid] :as req}]
  (let [line (draft-line (sse/read-signals req))
        err (line-error @!state line)]
    (when-not err (swap! !state handle-command {:type :add-to-cart :sid sid :line line}))
    (sse/sse-response (sse/patch-signals {:_error (or err "")}))))

(defn app
  "The Ring handler for one state atom and hub."
  [!state hub]
  (let [command (fn [f] {:handler (fn [req] (f req) no-content) :middleware [wrap-datastar-request]})
        apply! (fn [cmd] (swap! !state handle-command cmd))]
    (ring/ring-handler
     (ring/router
      [["/" {:get (fn [{:keys [sid]}]
                    (sse/html-response
                     (page "Brew Board" draft-signals "/customer/stream"
                           (view-for @!state {:kind :customer :sid sid}))))}]
       ["/board" {:get (fn [_]
                         (sse/html-response
                          (page "Barista board" nil "/board/stream" (view-for @!state {:kind :board}))))}]
       ["/customer/stream" {:get (fn [{:keys [sid] :as req}]
                                   ((stream-handler hub !state {:kind :customer :sid sid}) req))}]
       ["/board/stream" {:get (stream-handler hub !state {:kind :board})}]
       ["/draft" {:post {:handler (partial choose-draft !state) :middleware [wrap-datastar-request]}}]
       ["/cart/items" {:post {:handler (partial add-item !state) :middleware [wrap-datastar-request]}}]
       ["/cart/items/:idx" {:delete (command (fn [{:keys [sid path-params]}]
                                               (apply! {:type :remove-from-cart :sid sid
                                                        :idx (parse-long (:idx path-params))})))}]
       ["/orders" {:post (command (fn [{:keys [sid] :as req}]
                                    (apply! {:type :place-order :sid sid
                                             :customer (:customer (sse/read-signals req))})))}]
       ["/orders/:id/advance" {:post (command (fn [{:keys [path-params]}]
                                                (apply! {:type :advance :id (parse-long (:id path-params))})))}]
       ["/stock/:milk/toggle" {:post (command (fn [{:keys [path-params]}]
                                                (apply! {:type :toggle-stock :milk (keyword (:milk path-params))})))}]])
     (ring/create-default-handler)
     {:middleware [wrap-params wrap-cookies wrap-sid]})))

(defn watch!
  "Broadcast to the hub on every state change."
  [!state hub]
  (add-watch !state ::broadcast (fn [_ _ old new] (when (not= old new) (broadcast! hub new)))))

;; ---------------------------------------------------------------------------
;; Server

(defonce hub (make-hub))
(defonce !server (atom nil))

(defn stop! []
  (when-let [s @!server]
    @(hk/server-stop! s {:timeout 100})
    (reset! !server nil)))

(defn start!
  "Start on port (0 lets the OS pick). Returns the port."
  [port]
  (stop!)
  (watch! !state hub)
  (reset! !server (hk/run-server (app !state hub) {:port port :legacy-return-value? false}))
  (hk/server-port @!server))

;; ---------------------------------------------------------------------------
;; Demo: two held-open streams read on background threads while commands run.

(defn- visible-text [event]
  (let [t (-> event (string/replace #"(?m)^data: elements " "") (string/replace #"<[^>]+>" " ")
              (string/replace #"\s+" " ") string/trim
              (string/replace #"^Brew Board .*?\+\$\d\.\d\d (?=[A-Z])(?!Medium|Large|Double)" "Brew Board … ")
              (string/replace #"(Add to cart|advance|remove|Place order) ?" ""))]
    (cond-> t (> (count t) 160) (-> (subs 0 160) (str "…")))))

(defn- print-events!
  "Read SSE lines from a stream until it ends; print one summary line per event.
  (An explicit iterator loop: iterator-seq reads ahead in chunks and would block.)"
  [who lines]
  (future
    (let [it (.iterator lines)]
      (try
        (loop [buf []]
          (when (.hasNext it)
            (let [line (.next it)]
              (if (string/blank? line)
                (let [ev (string/join "\n" buf)]
                  (locking *out*
                    (println (str "   [" who "] " (second (re-find #"event: (\S+)" ev))
                                  "  " (visible-text (string/replace ev #"(?m)^event: .*\n?" "")))))
                  (recur []))
                (recur (conj buf line))))))
        (catch Exception _ nil)))))

(defn- open-stream [client base path]
  (-> (.send client (-> (HttpRequest/newBuilder (URI. (str base path))) .GET .build)
             (HttpResponse$BodyHandlers/ofLines))
      .body))

(defn- command! [client base method path signals note]
  (Thread/sleep 300)
  (locking *out* (println (str "\n── " method " " path (when signals (str " " (json/generate-string signals)))
                               "\n   " note)))
  (let [query (when (and signals (= "GET" method))
                (str "?datastar=" (URLEncoder/encode (json/generate-string signals) "UTF-8")))
        resp (.send client (-> (HttpRequest/newBuilder (URI. (str base path query)))
                               (.header "Datastar-Request" "true")
                               (.method method (if (and signals (not= "GET" method))
                                                 (HttpRequest$BodyPublishers/ofString (json/generate-string signals))
                                                 (HttpRequest$BodyPublishers/noBody)))
                               .build)
                    (HttpResponse$BodyHandlers/ofString))]
    (locking *out* (println "   ←" (.statusCode resp) (string/trim (.body resp))))))

(defn -main [& _]
  (reset! !state initial-state)
  (let [port (start! 0)
        base (str "http://localhost:" port)
        ada (-> (HttpClient/newBuilder) (.version HttpClient$Version/HTTP_1_1)
                (.cookieHandler (CookieManager.)) .build)
        sam (-> (HttpClient/newBuilder) (.version HttpClient$Version/HTTP_1_1) .build)]
    (println "Part 6: live. Brew Board on" base "(at the REPL: (start! 8080), open / and /board side by side)")
    (.send ada (-> (HttpRequest/newBuilder (URI. (str base "/"))) .GET .build) (HttpResponse$BodyHandlers/discarding))
    (println "Ada loads / (gets a sid cookie). Ada and Sam each open one stream; each gets its whole view at once:")
    (let [ada-lines (open-stream ada base "/customer/stream")
          sam-lines (open-stream sam base "/board/stream")
          readers [(print-events! "ada" ada-lines) (print-events! "sam" sam-lines)]]
      (command! ada base "POST" "/draft" {:drink "espresso" :size "medium" :milk "oat"}
                "Ada picks Espresso. Choosing a drink is a command now: the server stores her draft and answers the signals that no longer fit; her stream re-renders the drop-downs.")
      (command! ada base "POST" "/draft" {:drink "latte" :size "single" :milk ""}
                "Back to Latte: single isn't a latte size and she needs a milk.")
      (command! ada base "POST" "/cart/items" {:drink "latte" :size "medium" :milk "oat" :qty 1 :customer ""}
                "Ada adds a medium oat latte. The command answers only $_error; her stream sends her new view. Sam's view didn't change, so Sam gets nothing.")
      (command! ada base "POST" "/orders" {:customer "Ada"}
                "Ada places the order. Both views changed, so both streams get a patch.")
      (command! sam base "POST" "/orders/1/advance" nil
                "Sam advances #1 to making. Commands answer 204; the streams carry the change.")
      (command! sam base "POST" "/orders/1/advance" nil "Sam advances #1 to ready.")
      (command! sam base "POST" "/stock/oat/toggle" nil
                "Sam marks oat milk sold out: Ada's drop-down shows it with no extra code.")
      (Thread/sleep 300)
      (println "\nOpen streams in the hub:" (count @hub))
      (run! #(.close %) [ada-lines sam-lines])
      (run! #(deref % 1000 nil) readers)
      (Thread/sleep 200)
      (println "After both clients disconnect (http-kit :on-close → unsubscribe!):" (count @hub))
      (stop!)))
  (shutdown-agents))
