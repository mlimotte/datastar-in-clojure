(ns lesson05.routes
  "Part 5: shaping the backend. Pages are GETs that return whole HTML documents. Queries are
  GETs that return patches. Commands are POST/DELETE requests that change server state and,
  in this part, answer with patches for exactly the regions they changed (fragment-per-action).
  Part 6 replaces those answers with one live stream per page."
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [hiccup2.core :as h]
            [lesson05.sse :as sse]
            [org.httpkit.server :as hk]
            [reitit.ring :as ring]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]])
  (:import (java.net CookieManager URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
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
;; State

(def initial-state {:sold-out #{} :carts {} :orders (sorted-map) :next-id 1})

(defonce !state (atom initial-state))

(def next-status {:placed :making :making :ready :ready :picked-up})

(defn line-error
  "Why a draft line can't go in a cart, or nil when it can."
  [{:keys [sold-out]} {:keys [drink size milk qty]}]
  (let [{:keys [sizes milks] :as d} (drinks drink)]
    (cond
      (nil? d)                              "Pick a drink."
      (not (some #{size} sizes))            "Pick a size this drink comes in."
      (and (seq milks) (not (some #{milk} milks))) "Pick a milk."
      (and (empty? milks) milk)             "This drink takes no milk."
      (sold-out milk)                       (str (label milk) " milk is sold out.")
      (not (pos-int? qty))                  "Quantity must be at least 1.")))

(defn handle-command
  "Apply one command to the state. Pure: returns the new state. A command that does not apply
  (an invalid line, an empty cart, an unknown order) returns the state unchanged."
  [state {:keys [type sid line idx customer id milk]}]
  ;; EXERCISE(handle-command): Return the next state for each command type:
  ;; :add-to-cart (append line to sid's cart when line-error is nil), :remove-from-cart (drop the
  ;; line at idx), :place-order (turn a non-empty cart into a :placed order for a non-blank
  ;; customer, then empty the cart), :advance (move an order one step along next-status; an order
  ;; that reaches :picked-up leaves :orders), :toggle-stock (add or remove milk from :sold-out).
  (case type
    :add-to-cart
    (cond-> state
      (nil? (line-error state line)) (update-in [:carts sid] (fnil conj []) line))

    :remove-from-cart
    (update-in state [:carts sid]
               #(into [] (keep-indexed (fn [i l] (when (not= i idx) l))) %))

    :place-order
    (let [lines (get-in state [:carts sid])]
      (if (or (empty? lines) (string/blank? customer))
        state
        (-> state
            (assoc-in [:orders (:next-id state)]
                      {:id (:next-id state) :sid sid :customer (string/trim customer)
                       :status :placed :lines lines})
            (update :next-id inc)
            (update :carts dissoc sid))))

    :advance
    (if-let [status (get-in state [:orders id :status])]
      (if (= :picked-up (next-status status))
        (update state :orders dissoc id)
        (assoc-in state [:orders id :status] (next-status status)))
      state)

    :toggle-stock
    (update state :sold-out #(if (% milk) (disj % milk) (conj % milk)))

    state)
  ;; END EXERCISE
  )

;; ---------------------------------------------------------------------------
;; Views: pure functions from state to hiccup. Every patchable region has an id.

(def datastar-src
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.4/bundles/datastar.js")

(defn page [title & body]
  (str "<!DOCTYPE html>"
       (h/html
        [:html {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title title]
          [:script {:type "module" :src datastar-src}]]
         [:body body]])))

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

(defn cart-view [state sid]
  (let [lines (get-in state [:carts sid])]
    [:section#cart
     [:h2 "Cart"]
     (if (empty? lines)
       [:p.muted "Empty."]
       [:ul
        (map-indexed
         (fn [i {:keys [drink size milk qty] :as line}]
           [:li (str qty " × " (label size) " " (when milk (str (label milk) " ")) (get-in drinks [drink :name])
                     " — " (dollars (line-total line)))
            " " [:button {:data-on:click (str "@delete('/cart/items/" i "')")} "remove"]])
         lines)])
     [:p "Total: " (dollars (reduce + (map line-total lines)))]
     [:input#customer {:data-bind:customer "" :placeholder "Your name"}]
     ;; The server re-renders #cart after every cart command, so it can decide `disabled` itself.
     [:button#place-order (cond-> {:data-on:click "@post('/orders')"} (empty? lines) (assoc :disabled ""))
      "Place order"]]))

(defn my-orders-view [state sid]
  [:section#my-orders
   [:h2 "Your orders"]
   [:ul (for [{:keys [id customer status] :as o} (vals (:orders state)) :when (= sid (:sid o))]
          [:li (str "#" id " for " customer ": " (name status))])]])

(defn customer-body [state sid]
  [:main#app
   [:h1 "Brew Board"]
   [:section#draft {:data-signals (json/generate-string {:drink "latte" :size "small" :milk "whole"
                                                         :qty 1 :customer ""})}
    [:select#drink {:data-bind:drink "" :data-on:change "@get('/drink-options')"}
     (for [{:keys [id name]} menu] [:option {:value (clojure.core/name id)} name])]
    (drink-options-view :latte (:sold-out state))
    [:input {:type "number" :min "1" :data-bind:qty ""}]
    [:button#add-to-cart {:data-on:click "@post('/cart/items')"} "Add to cart"]
    [:p#draft-error]]
   (cart-view state sid)
   (my-orders-view state sid)])

(defn board-orders-view [state]
  [:section#board-orders
   [:h2 "Orders"]
   [:ul (for [{:keys [id customer status lines]} (vals (:orders state))]
          [:li (str "#" id " " customer " (" (name status) "): "
                    (string/join ", " (map #(str (:qty %) "× " (get-in drinks [(:drink %) :name])) lines)))
           " " [:button {:data-on:click (str "@post('/orders/" id "/advance')")} "advance"]])]])

(defn stock-view [state]
  [:section#stock
   [:h2 "Milk"]
   (for [m [:whole :oat :almond]]
     [:button {:id (str "stock-" (name m))
               :data-on:click (str "@post('/stock/" (name m) "/toggle')")}
      (str (label m) (when ((:sold-out state) m) " (sold out)"))])])

(defn board-body [state]
  [:main#app [:h1 "Barista board"] (board-orders-view state) (stock-view state)])

;; ---------------------------------------------------------------------------
;; Command responses: fragment-per-action. Each command must know what it changed.

(defn command-response
  "The patches to answer a command with, given the state after it ran.
  Cart commands patch #cart; place-order patches #cart and #my-orders and clears $customer;
  board commands patch the board region they touched."
  [state {:keys [type sid]}]
  ;; EXERCISE(command-response): Return a one-shot SSE response that patches exactly the regions
  ;; this command type changed (see the docstring), rendered from the new state.
  (case type
    (:add-to-cart :remove-from-cart)
    (sse/sse-response (sse/patch-elements (html (cart-view state sid))))

    :place-order
    (sse/sse-response (sse/patch-elements (html (cart-view state sid)))
                      (sse/patch-elements (html (my-orders-view state sid)))
                      (sse/patch-signals {:customer ""}))

    :advance
    (sse/sse-response (sse/patch-elements (html (board-orders-view state))))

    :toggle-stock
    (sse/sse-response (sse/patch-elements (html (stock-view state)))))
  ;; END EXERCISE
  )

;; ---------------------------------------------------------------------------
;; Middleware

(defn wrap-datastar-request
  "Reject a command that did not come from Datastar's fetch (no `Datastar-Request: true` header)
  with 403. A cross-origin page can't add that header without a CORS preflight."
  [handler]
  ;; EXERCISE(wrap-datastar-request): Return a handler that calls `handler` only when the request
  ;; header datastar-request is "true", and answers 403 otherwise.
  (fn [req]
    (if (= "true" (get-in req [:headers "datastar-request"]))
      (handler req)
      {:status 403 :headers {"Content-Type" "text/plain"} :body "Datastar requests only"}))
  ;; END EXERCISE
  )

(defn wrap-sid
  "Give every browser a `sid` cookie (toy: unsigned) and put it on the request as :sid."
  [handler]
  (fn [req]
    (if-let [sid (get-in req [:cookies "sid" :value])]
      (handler (assoc req :sid sid))
      (let [sid (str (random-uuid))]
        (assoc-in (handler (assoc req :sid sid)) [:cookies "sid"] {:value sid :path "/" :same-site :lax})))))

;; ---------------------------------------------------------------------------
;; Handlers: read the request, build a command, apply it, answer with patches.

(defn signal->keyword [s] (when-not (string/blank? (str s)) (keyword s)))

(defn draft-line [{:keys [drink size milk qty]}]
  {:drink (signal->keyword drink) :size (signal->keyword size) :milk (signal->keyword milk)
   :qty   (cond-> qty (string? qty) parse-long)})

(defn run-command!
  "Apply cmd to the state atom and answer with command-response."
  [!state cmd]
  (command-response (swap! !state handle-command cmd) cmd))

(defn drink-options
  "Query: re-render the drop-downs for $drink and fix $size/$milk if they're now invalid."
  [!state req]
  (let [{:keys [drink size milk]} (sse/read-signals req)
        drink (or (drinks (signal->keyword drink)) (drinks :latte))
        sold-out (:sold-out @!state)
        valid-milks (remove sold-out (:milks drink))
        fix (cond-> {}
              (not (some #{(signal->keyword size)} (:sizes drink)))
              (assoc :size (name (first (:sizes drink))))
              (not (some #{(signal->keyword milk)} valid-milks))
              (assoc :milk (or (some-> (first valid-milks) name) "")))]
    (apply sse/sse-response
           (sse/patch-elements (html (drink-options-view (:id drink) sold-out)))
           (when (seq fix) [(sse/patch-signals fix)]))))

(defn add-item [!state {:keys [sid] :as req}]
  (let [line (draft-line (sse/read-signals req))]
    (if-let [err (line-error @!state line)]
      (sse/sse-response (sse/patch-elements (html [:p#draft-error err])))
      (update (run-command! !state {:type :add-to-cart :sid sid :line line})
              :body str (sse/patch-elements (html [:p#draft-error]))))))

(defn app
  "The Ring handler for one state atom. The route table is the shape of the backend:
  pages, one query, and commands (each guarded by wrap-datastar-request)."
  [!state]
  (let [command (fn [f] {:handler f :middleware [wrap-datastar-request]})]
    (ring/ring-handler
     (ring/router
      [["/" {:get (fn [{:keys [sid]}] (sse/html-response (page "Brew Board" (customer-body @!state sid))))}]
       ["/board" {:get (fn [_] (sse/html-response (page "Barista board" (board-body @!state))))}]
       ["/drink-options" {:get (partial drink-options !state)}]
       ["/cart/items" {:post (command (partial add-item !state))}]
       ["/cart/items/:idx" {:delete (command (fn [{:keys [sid path-params]}]
                                               (run-command! !state {:type :remove-from-cart :sid sid
                                                                     :idx (parse-long (:idx path-params))})))}]
       ["/orders" {:post (command (fn [{:keys [sid] :as req}]
                                    (run-command! !state {:type :place-order :sid sid
                                                          :customer (:customer (sse/read-signals req))})))}]
       ["/orders/:id/advance" {:post (command (fn [{:keys [path-params]}]
                                                (run-command! !state {:type :advance
                                                                      :id (parse-long (:id path-params))})))}]
       ["/stock/:milk/toggle" {:post (command (fn [{:keys [path-params]}]
                                                (run-command! !state {:type :toggle-stock
                                                                      :milk (keyword (:milk path-params))})))}]])
     (ring/create-default-handler)
     {:middleware [wrap-params wrap-cookies wrap-sid]})))

;; ---------------------------------------------------------------------------
;; Server

(defonce !server (atom nil))

(defn stop! []
  (when-let [s @!server]
    (hk/server-stop! s)
    (reset! !server nil)))

(defn start!
  "Start on port (0 lets the OS pick). Returns the port."
  [port]
  (stop!)
  (reset! !server (hk/run-server (app !state) {:port port :legacy-return-value? false}))
  (hk/server-port @!server))

;; ---------------------------------------------------------------------------
;; Demo

(defn- request [client base method path signals]
  (let [b (HttpRequest/newBuilder (URI. (str base path
                                             (when (and signals (#{"GET" "DELETE"} method))
                                               (str "?datastar=" (java.net.URLEncoder/encode
                                                                  (json/generate-string signals) "UTF-8"))))))]
    (-> (cond-> b
          (not= "GET" method) (.header "Datastar-Request" "true")
          true (.method method (if (and signals (#{"POST" "PUT" "PATCH"} method))
                                 (HttpRequest$BodyPublishers/ofString (json/generate-string signals))
                                 (HttpRequest$BodyPublishers/noBody))))
        .build
        (as-> r (.send client r (HttpResponse$BodyHandlers/ofString))))))

(defn- show [client base method path signals note]
  (println (str "\n── " method " " path (when signals (str "  signals " (json/generate-string signals)))))
  (println (str "   " note))
  (let [resp (request client base method path signals)]
    (println (str "   ← " (.statusCode resp) " "
                  (-> resp .headers (.firstValue "content-type") (.orElse ""))))
    (doseq [l (string/split-lines (.body resp))] (println (str "   │ " l)))
    resp))

(defn demo!
  "Run the narrated demo: start a server, make real requests, print what happens, stop."
  []
  (reset! !state initial-state)
  (let [port (start! 0)
        base (str "http://localhost:" port)
        client (-> (HttpClient/newBuilder) (.cookieHandler (CookieManager.)) .build)]
    (println "Part 5: routes. Brew Board on" base "(at the REPL: (start! 8080), open / and /board)")
    (println "\nEvery command answers with patches for exactly what it changed.")
    (request client base "GET" "/" nil)
    (println "GET / → full page; the browser now has a sid cookie.")
    (show client base "GET" "/drink-options" {:drink "espresso" :size "small" :milk "whole"}
          "Query: Ada picks Espresso. The server re-renders the drop-downs and fixes $size/$milk.")
    (show client base "POST" "/cart/items" {:drink "latte" :size "medium" :milk "oat" :qty 1}
          "Command: add a medium oat latte. Response patches #cart (and clears #draft-error).")
    (show client base "POST" "/orders" {:customer "Ada"}
          "Command: place the order. Patches #cart, #my-orders, and clears $customer.")
    (show client base "POST" "/orders/1/advance" nil
          "Command from Sam's board: placed → making. Patches #board-orders only.")
    (println "   Ada's page still says 'placed': nothing pushed #my-orders to her. Part 6 fixes that.")
    (let [anon (HttpRequest/newBuilder (URI. (str base "/stock/oat/toggle")))
          resp (.send client (-> anon (.POST (HttpRequest$BodyPublishers/noBody)) .build)
                      (HttpResponse$BodyHandlers/ofString))]
      (println "\n── POST /stock/oat/toggle without Datastar-Request")
      (println "   ←" (.statusCode resp) (.body resp) "(wrap-datastar-request)"))
    (stop!)))

(defn -main [& _]
  (demo!)
  (shutdown-agents))
