(ns lesson07.native-shell
  "Part 7: replacing the React Native app. A thin native shell (WKWebView / Android WebView)
  loads the same routes as the browser and adds `BrewShell/1` to its User-Agent. The server
  renders shell pages without web navigation, serves the shell's navigation rules as JSON, and
  talks to native code through a small bridge:
    native → web  a window CustomEvent `brew:native` the page turns into signals and a POST
    web → native  an execute-script event that calls window.BrewShell.post(msg)"
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [hiccup.util :refer [raw-string]]
            [hiccup2.core :as h]
            [lesson07.brew :as brew]
            [lesson07.sse :as sse]
            [org.httpkit.server :as hk]
            [reitit.ring :as ring]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]])
  (:import (java.net CookieManager URI)
           (java.net.http HttpClient HttpClient$Version HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)))

;; ---------------------------------------------------------------------------
;; Who is asking

(defn shell-request?
  "True when the request comes from the Brew Board native shell."
  [req]
  ;; EXERCISE(shell-request): The shell appends `BrewShell/1` to its User-Agent. Return true when
  ;; the request's user-agent header contains it, false otherwise (including no header).
  (boolean (some-> (get-in req [:headers "user-agent"]) (string/includes? "BrewShell/1")))
  ;; END EXERCISE
  )

;; ---------------------------------------------------------------------------
;; The bridge

(def bridge-shim
  "Defined on every page. In the shell it forwards to native code; in a browser it does nothing."
  "window.BrewShell = { post(msg) {
  if (window.webkit?.messageHandlers?.brew) window.webkit.messageHandlers.brew.postMessage(msg);
  else if (window.BrewAndroid) window.BrewAndroid.postMessage(JSON.stringify(msg));
} };")

(defn bridge-script
  "An SSE event that calls window.BrewShell.post(msg) in the browser."
  [msg]
  ;; EXERCISE(bridge-script): Return an execute-script event whose script calls
  ;; window.BrewShell.post with msg as a JSON object literal. The JSON must be safe inside a
  ;; <script> tag: a customer named "</script>" must not end it.
  (sse/execute-script (str "window.BrewShell.post("
                           (string/replace (json/generate-string msg) "</" "<\\/")
                           ")"))
  ;; END EXERCISE
  )

(defn ready-notifications
  "Notifications for orders that became :ready between two states: [{:sid … :message …}]."
  [old new]
  ;; EXERCISE(ready-notifications): Return one {:sid … :message …} per order that is :ready in
  ;; new but was not :ready in old, in order-id order. The message reads
  ;; "Ada, your order #1 is ready."
  (for [{:keys [id sid customer status]} (vals (:orders new))
        :when (and (= :ready status) (not= :ready (get-in old [:orders id :status])))]
    {:sid sid :message (str customer ", your order #" id " is ready.")})
  ;; END EXERCISE
  )

;; ---------------------------------------------------------------------------
;; Navigation rules the shell fetches at launch. Changing them is a deploy, not an app release.

(def path-config
  {:rules [{:patterns ["^/$"] :presentation "root"}
           {:patterns ["^/orders/\\d+$"] :presentation "push"}]})

;; ---------------------------------------------------------------------------
;; Views. A viewer is {:kind :customer|:board :sid … :shell? bool}.

(def datastar-src
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.4/bundles/datastar.js")

(defn html [hiccup] (str (h/html hiccup)))

(def default-draft
  "The draft a tab starts with; matches the draft signals on the page."
  {:drink :latte :size :small :milk :whole})

(defn drink-options-view
  "The size and milk drop-downs for one drink. The option lists are always server HTML.
  `choice` is the stored draft {:size … :milk …}; its options render `selected`. The morph
  rewrites options by position, so without `selected` a select can show one size while
  $size holds another. Each select posts the draft on change, so the stored choice is real."
  [drink sold-out {:keys [size milk]}]
  (let [{:keys [sizes milks]} (brew/drinks drink)
        option (fn [k selected? text & [disabled?]]
                 [:option (cond-> {:value (name k)}
                            selected? (assoc :selected "")
                            disabled? (assoc :disabled "")) text])]
    [:div#drink-options
     [:select#size {:data-bind:size "" :data-on:change "@post('/draft')"}
      (for [s sizes] (option s (= s size) (str (brew/label s) " +" (brew/dollars (brew/size-delta s)))))]
     (if (seq milks)
       [:select#milk {:data-bind:milk "" :data-on:change "@post('/draft')"}
        (for [m milks]
          (if (sold-out m)
            (option m (= m milk) (str (brew/label m) " (sold out)") true)
            (option m (= m milk) (brew/label m))))]
       [:p.muted "No milk"])]))

(defn- line-text [{:keys [drink size milk qty] :as line}]
  (str qty " × " (brew/label size) " " (when milk (str (brew/label milk) " "))
       (get-in brew/drinks [drink :name]) " — " (brew/dollars (brew/line-total line))))

(defn- customer-view [state {:keys [sid tabid shell?]}]
  (let [lines (get-in state [:carts sid])
        {:keys [draft error]} (get-in state [:tabs tabid])
        draft (or draft default-draft)]
    [:main#app
     ;; In the shell the native title bar shows the title.
     (when-not shell? [:h1 "Brew Board"])
     [:section#draft
      [:select#drink {:data-bind:drink "" :data-on:change "@post('/draft')"}
       (for [{:keys [id name]} brew/menu]
         [:option (cond-> {:value (clojure.core/name id)}
                    (= id (:drink draft)) (assoc :selected "")) name])]
      (drink-options-view (:drink draft) (:sold-out state) draft)
      [:input {:type "number" :min "1" :data-bind:qty ""}]
      [:button#add-to-cart {:data-on:click "@post('/cart/items')"} "Add to cart"]
      ;; The error is server state: the tab's last failed command put it there.
      (when error [:p.error error])]
     [:section#cart
      [:h2 "Cart"]
      (if (empty? lines)
        [:p.muted "Empty."]
        [:ul (map-indexed (fn [i line]
                            [:li (line-text line) " "
                             [:button {:data-on:click (str "@delete('/cart/items/" i "')")} "remove"]])
                          lines)])
      [:p "Total: " (brew/dollars (reduce + (map brew/line-total lines)))]
      [:input#customer {:data-bind:customer "" :placeholder "Your name"}]
      [:button#place-order {:data-on:click "@post('/orders')"} "Place order"]]
     [:section#my-orders
      [:h2 "Your orders"]
      [:ul (for [{:keys [id customer status] :as o} (vals (:orders state)) :when (= sid (:sid o))]
             [:li {:id (str "order-" id)}
              [:a {:href (str "/orders/" id)} (str "#" id " for " customer)] ": " (name status)])]]]))

(defn- board-view [state _viewer]
  [:main#app
   [:h1 "Barista board"]
   [:section#board-orders
    [:h2 "Orders"]
    [:ul (for [{:keys [id customer status lines]} (vals (:orders state))]
           [:li {:id (str "order-" id)}
            (str "#" id " " customer " (" (name status) "): "
                 (string/join ", " (map #(str (:qty %) "× " (get-in brew/drinks [(:drink %) :name])) lines)))
            " " [:button {:data-on:click (str "@post('/orders/" id "/advance')")} "advance"]])]]
   [:section#stock
    [:h2 "Milk"]
    (for [m [:whole :oat :almond]]
      [:button {:id (str "stock-" (name m)) :data-on:click (str "@post('/stock/" (name m) "/toggle')")}
       (str (brew/label m) (when ((:sold-out state) m) " (sold out)"))])]])

(defn view-for [state {:keys [kind] :as viewer}]
  (case kind
    :customer (customer-view state viewer)
    :board    (board-view state viewer)))

(defn page
  "A full page. The web gets a nav bar; the shell does not (it draws native navigation).
  The bridge listener turns the shell's {type: 'push-token'} message into $pushToken and a POST
  that sends only that signal, written as one `&&` expression. The tab id is seeded once per page
  load on <body>, before the child that opens the stream."
  [{:keys [title shell? signals stream-url]} view]
  (str "<!DOCTYPE html>"
       (h/html
        [:html {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title title]
          [:script (raw-string bridge-shim)]
          [:script {:type "module" :src datastar-src}]]
         [:body (when signals {:data-signals (json/generate-string signals)
                               :data-signals:tabid__ifmissing "crypto.randomUUID()"})
          (when-not shell?
            [:nav.web-nav [:a {:href "/"} "Order"] " · " [:a {:href "/board"} "Board"]])
          [:div {:data-on:brew:native__window
                 (str "evt.detail.type === 'push-token' && ($pushToken = evt.detail.token, "
                      "@post('/native/device', {filterSignals: {include: /^pushToken$/}}))")}]
          (when stream-url [:div {:data-init (str "@get('" stream-url "', {retry: 'always'})")}])
          view]])))

;; ---------------------------------------------------------------------------
;; The hub (copied from part 6), plus notify! for bridge messages.

(defn make-hub [] (atom {}))

(defn subscribe! [hub id send-fn viewer]
  (swap! hub assoc id {:send-fn send-fn :viewer viewer :last nil}))

(defn unsubscribe! [hub id] (swap! hub dissoc id))

(defn broadcast!
  "Send each subscriber its whole view when it changed. Returns the ids sent to."
  [hub state]
  (locking hub
    (into []
          (keep (fn [[id {:keys [send-fn viewer last]}]]
                  (let [view (html (view-for state viewer))]
                    (when (not= view last)
                      (send-fn (sse/patch-elements view))
                      (swap! hub assoc-in [id :last] view)
                      id))))
          @hub)))

(defn notify!
  "Send each notification to every open stream of its sid as a bridge call (haptic + local
  notification). Returns the notifications that reached no open stream."
  [hub notes]
  (locking hub
    (doall
     (remove (fn [{:keys [sid message]}]
               (let [subs (filter #(= sid (get-in % [:viewer :sid])) (vals @hub))]
                 (doseq [{:keys [send-fn]} subs]
                   (send-fn (bridge-script {:type "notify" :title "Brew Board" :body message
                                            :haptic "success"})))
                 (seq subs)))
             notes))))

(defn stream-handler
  "Hold a GET open. A customer viewer has a :tabid and the :draft its tab sent: opening adopts the
  draft for that tab, closing removes the tab (part 6's stream-lifecycle, inlined)."
  [hub !state {:keys [sid tabid draft] :as viewer}]
  (fn [req]
    (let [id (random-uuid)]
      (hk/as-channel req
                     {:on-open  (fn [ch]
                                  (hk/send! ch {:status 200
                                                :headers (assoc sse/sse-headers "Connection" "keep-alive")}
                                            false)
                                  (when tabid
                                    (swap! !state brew/handle-command
                                           {:type :open-tab :sid sid :tabid tabid :stream id :draft draft}))
                                  (subscribe! hub id #(hk/send! ch % false) (dissoc viewer :draft))
                                  (broadcast! hub @!state))
                      :on-close (fn [_ch _status]
                                  (unsubscribe! hub id)
                                  (when tabid
                                    (swap! !state brew/handle-command {:type :close-tab :tabid tabid :stream id})))}))))

(defn push!
  "Toy stand-in for APNs/FCM: a backgrounded app has no open stream, so a real server would send
  a push notification to the device token here."
  [token message]
  (println (str "   [push] → " token ": " message)))

(defn watch!
  "On every state change: re-render for the hub, then deliver ready notifications. A notification
  with no open stream falls back to a push to the device token, when there is one."
  [!state hub]
  (add-watch !state ::live
             (fn [_ _ old new]
               (when (not= old new)
                 (broadcast! hub new)
                 (doseq [{:keys [sid message]} (notify! hub (ready-notifications old new))]
                   (when-let [token (get-in new [:devices sid])]
                     (push! token message)))))))

;; ---------------------------------------------------------------------------
;; Routes: part 6's routes (POST /draft included), shell-aware, plus /orders/:id and /native/*.

(defn wrap-datastar-request [handler]
  (fn [req]
    (if (= "true" (get-in req [:headers "datastar-request"]))
      (handler req)
      {:status 403 :headers {"Content-Type" "text/plain"} :body "Datastar requests only"})))

(defn wrap-sid [handler]
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

(def draft-signals
  {:drink "latte" :size "small" :milk "whole" :qty 1 :customer "" :pushToken ""})

(defn signals->draft [signals]
  (-> signals (select-keys [:drink :size :milk]) (update-vals signal->keyword)))

(defn choose-draft
  "Command: store this tab's draft, fitted to the drink, so the stream re-renders the
  drop-downs from it. Answer with the size/milk signals that no longer fit, or 204."
  [!state {:keys [sid] :as req}]
  (let [{:keys [tabid] :as signals} (sse/read-signals req)
        draft (signals->draft signals)
        fitted (get-in (swap! !state brew/handle-command {:type :choose-draft :sid sid :tabid tabid :draft draft})
                       [:tabs tabid :draft])
        fix (into {} (for [k [:size :milk] :when (not= (k draft) (k fitted))]
                       [k (or (some-> (k fitted) name) "")]))]
    (if (seq fix) (sse/sse-response (sse/patch-signals fix)) no-content)))

(defn order-page
  "A screen the shell pushes onto its navigation stack (see path-config)."
  [!state {:keys [sid path-params] :as req}]
  (if-let [{:keys [id customer status lines]}
           (some-> (get-in @!state [:orders (parse-long (:id path-params))]) (#(when (= sid (:sid %)) %)))]
    (sse/html-response
     (page {:title (str "Order #" id) :shell? (shell-request? req)}
           [:main#app
            [:p (str customer ", order #" id " is " (name status) ".")]
            [:ul (for [l lines] [:li (line-text l)])]]))
    {:status 404 :headers {"Content-Type" "text/plain"} :body "No such order"}))

(defn app [!state hub]
  (let [command (fn [f] {:handler (fn [req] (f req) no-content) :middleware [wrap-datastar-request]})
        apply! (fn [cmd] (swap! !state brew/handle-command cmd))
        customer (fn [{:keys [sid] :as req}] {:kind :customer :sid sid :shell? (shell-request? req)})
        ;; The stream GET carries the tab's signals: its id and the draft to adopt.
        tab (fn [req] (let [{:keys [tabid] :as signals} (sse/read-signals req)]
                        (assoc (customer req) :tabid tabid :draft (signals->draft signals))))]
    (ring/ring-handler
     (ring/router
      [["/" {:get (fn [req]
                    (sse/html-response
                     (page {:title "Brew Board" :shell? (shell-request? req) :signals draft-signals
                            :stream-url "/customer/stream"}
                           (view-for @!state (customer req)))))}]
       ["/board" {:get (fn [req]
                         (sse/html-response
                          (page {:title "Barista board" :shell? (shell-request? req) :stream-url "/board/stream"}
                                (view-for @!state {:kind :board}))))}]
       ["/customer/stream" {:get (fn [req] ((stream-handler hub !state (tab req)) req))}]
       ["/board/stream" {:get (stream-handler hub !state {:kind :board})}]
       ["/draft" {:post {:handler (partial choose-draft !state) :middleware [wrap-datastar-request]}}]
       ["/cart/items" {:post (command (fn [{:keys [sid] :as req}]
                                        (let [{:keys [tabid] :as signals} (sse/read-signals req)]
                                          (apply! {:type :add-to-cart :sid sid :tabid tabid
                                                   :line (draft-line signals)}))))}]
       ["/cart/items/:idx" {:delete (command (fn [{:keys [sid path-params] :as req}]
                                               (apply! {:type :remove-from-cart :sid sid
                                                        :tabid (:tabid (sse/read-signals req))
                                                        :idx (parse-long (:idx path-params))})))}]
       ["/orders" {:post (command (fn [{:keys [sid] :as req}]
                                    (let [{:keys [tabid customer]} (sse/read-signals req)]
                                      (apply! {:type :place-order :sid sid :tabid tabid
                                               :customer customer}))))}]
       ["/orders/:id" {:get (partial order-page !state)}]
       ["/orders/:id/advance" {:post (command (fn [{:keys [path-params]}]
                                                (apply! {:type :advance :id (parse-long (:id path-params))})))}]
       ["/stock/:milk/toggle" {:post (command (fn [{:keys [path-params]}]
                                                (apply! {:type :toggle-stock :milk (keyword (:milk path-params))})))}]
       ["/native/path-config" {:get (fn [_] {:status 200 :headers {"Content-Type" "application/json"}
                                             :body (json/generate-string path-config)})}]
       ["/native/device" {:post (command (fn [{:keys [sid] :as req}]
                                           (apply! {:type :register-device :sid sid
                                                    :token (:pushToken (sse/read-signals req))})))}]])
     (ring/create-default-handler)
     {:middleware [wrap-params wrap-cookies wrap-sid]})))

;; ---------------------------------------------------------------------------
;; Server

(defonce !state (atom brew/initial-state))
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
;; Demo: a "shell" client (BrewShell/1 user agent) and Sam's board.

(def shell-ua "Mozilla/5.0 (iPhone) AppleWebKit/605.1.15 BrewShell/1")

(defn- send-req [client base method path {:keys [signals ua]}]
  (.send client
         (-> (HttpRequest/newBuilder (URI. (str base path)))
             (.header "Datastar-Request" "true")
             (cond-> ua (.header "User-Agent" ua))
             (.method method (if signals
                               (HttpRequest$BodyPublishers/ofString (json/generate-string signals))
                               (HttpRequest$BodyPublishers/noBody)))
             .build)
         (HttpResponse$BodyHandlers/ofString)))

(defn- say [& xs] (locking *out* (apply println xs)))

(defn- watch-stream!
  "Open a stream; print a short line per view patch and the full text of each bridge call."
  [client base path ua signals]
  (let [lines (-> client
                  (.send (-> (HttpRequest/newBuilder
                              (URI. (str base path "?datastar="
                                         (java.net.URLEncoder/encode (json/generate-string signals) "UTF-8"))))
                             (.header "User-Agent" ua) .GET .build)
                         (HttpResponse$BodyHandlers/ofLines))
                  .body)
        it (.iterator lines)]
    {:lines lines
     :reader (future
               (try
                 (loop [buf []]
                   (when (.hasNext it)
                     (let [line (.next it)]
                       (if (string/blank? line)
                         (let [ev (string/join "\n" buf)]
                           (if (string/includes? ev "BrewShell.post")
                             (say (str "   [ada's shell] bridge call:\n"
                                       (string/join "\n" (map #(str "     │ " %) buf))))
                             (say (str "   [ada's shell] view patch: "
                                       (or (some->> (re-seq #"#\d+ for \w+</a>: \w+" ev) (map #(string/replace % "</a>" ""))
                                                    seq (string/join ", "))
                                           "(no orders)"))))
                           (recur []))
                         (recur (conj buf line))))))
                 (catch Exception _ nil)))}))

(defn demo!
  "Run the narrated demo: start a server, make real requests, print what happens, stop."
  []
  (reset! !state brew/initial-state)
  (let [port (start! 0)
        base (str "http://localhost:" port)
        ada (-> (HttpClient/newBuilder) (.version HttpClient$Version/HTTP_1_1)
                (.cookieHandler (CookieManager.)) .build)
        sam (-> (HttpClient/newBuilder) (.version HttpClient$Version/HTTP_1_1) .build)
        step (fn [note] (Thread/sleep 250) (say (str "\n── " note)))]
    (say "Part 7: the native shell. Brew Board on" base "(at the REPL: (start! 8080))")
    (step "The same route, two clients: a browser gets a web nav bar; the shell does not.")
    (say "   browser GET / has <nav>:" (string/includes? (.body (send-req sam base "GET" "/" {})) "<nav"))
    (say "   shell   GET / has <nav>:" (string/includes? (.body (send-req ada base "GET" "/" {:ua shell-ua})) "<nav"))
    (step "At launch the shell fetches its navigation rules. Changing them is a deploy, not an app release.")
    (say "   GET /native/path-config →" (.body (send-req ada base "GET" "/native/path-config" {:ua shell-ua})))
    (step "Native → web: the shell dispatches brew:native {type: push-token}; the page POSTs only $pushToken.")
    (say "   POST /native/device →" (.statusCode (send-req ada base "POST" "/native/device"
                                                           {:ua shell-ua :signals {:pushToken "apns-ada-7f3c"}})))
    (say "   stored:" (get-in @!state [:devices (some-> @!state :devices keys first)]))
    (step "Ada's shell opens its stream and orders two lattes, one at a time.")
    (let [tab "tab-ada-shell"
          {:keys [lines reader]} (watch-stream! ada base "/customer/stream" shell-ua (assoc draft-signals :tabid tab))]
      (doseq [_ (range 2)]
        (send-req ada base "POST" "/cart/items" {:ua shell-ua :signals {:tabid tab :drink "latte" :size "medium" :milk "oat" :qty 1}})
        (send-req ada base "POST" "/orders" {:ua shell-ua :signals {:tabid tab :customer "Ada"}}))
      (step "Sam advances #1 twice. When it turns ready, Ada's stream carries the view AND a bridge call.")
      (dotimes [_ 2] (send-req sam base "POST" "/orders/1/advance" {}))
      (send-req sam base "POST" "/orders/2/advance" {})
      (Thread/sleep 300)
      (step "Ada puts the app in the background: the WebView's stream closes.")
      (.close lines)
      (deref reader 1000 nil)
      (Thread/sleep 200)
      (say "   open streams:" (count @hub))
      (step "Sam marks #2 ready. No stream is open for Ada, so the server falls back to a push.")
      (send-req sam base "POST" "/orders/2/advance" {}))
    (stop!)))

(defn -main [& _]
  (demo!)
  (shutdown-agents))
