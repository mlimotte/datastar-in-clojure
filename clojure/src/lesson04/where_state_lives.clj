(ns lesson04.where-state-lives
  "Part 4: when to use a signal.
  The draft (drink, size, milk, qty) is input, so it lives in signals. The lists of sizes and
  milks depend on the menu and on stock, so the server renders them as HTML: the drop-down.
  The cart must survive a reload and be checked against stock, so it lives on the server,
  keyed by a sid cookie."
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [hiccup2.core :as h]
            [lesson04.sse :as sse]
            [org.httpkit.server :as hk]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.codec :as codec])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers)
           (java.util UUID)))

(def datastar-cdn
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.4/bundles/datastar.js")

;; ---------------------------------------------------------------- menu (from part 3)

(def menu
  (array-map
   :espresso   {:name "Espresso"   :base 300 :sizes [:single :double]       :milks []}
   :americano  {:name "Americano"  :base 350 :sizes [:small :large]         :milks []}
   :latte      {:name "Latte"      :base 450 :sizes [:small :medium :large] :milks [:whole :oat :almond]}
   :cappuccino {:name "Cappuccino" :base 425 :sizes [:small :medium]        :milks [:whole :oat]}
   :mocha      {:name "Mocha"      :base 500 :sizes [:small :medium :large] :milks [:whole :oat :almond]}
   :chai       {:name "Chai Latte" :base 450 :sizes [:medium :large]        :milks [:whole :oat :almond]}))

(def size-delta {:single 0 :double 100 :small 0 :medium 50 :large 100})
(def milk-delta {:whole 0 :oat 60 :almond 60})

(defn price-cents [{:keys [drink size milk]}]
  (+ (get-in menu [drink :base]) (size-delta size) (get milk-delta milk 0)))

(defn label [k] (string/capitalize (name k)))

(defn dollars [cents] (format "$%.2f" (/ cents 100.0)))

(defn ->draft
  "Turn signals (strings from the browser) into a draft with keywords. A value that is not a
  non-empty string, such as a number from a crafted request, becomes nil."
  [{:keys [drink size milk qty]}]
  (let [->kw #(when (string? %) (some-> % not-empty keyword))]
    {:drink (->kw drink)
     :size  (->kw size)
     :milk  (->kw milk)
     :qty   qty}))

(defn draft-error
  "Return why the draft cannot go in the cart, or nil when it can."
  [{:keys [drink size milk qty]} sold-out]
  (let [{:keys [name sizes milks]} (menu drink)]
    (cond
      (nil? name)                           "Pick a drink."
      (not (some #{size} sizes))            (str name " does not come in that size.")
      (and (seq milks) (nil? milk))         (str name " needs a milk.")
      (and milk (not (some #{milk} milks))) (str name " does not take that milk.")
      (sold-out milk)                       (str (label milk) " milk is sold out.")
      (not (and (int? qty) (<= 1 qty 10)))  "Quantity must be 1 to 10.")))

;; ---------------------------------------------------------------- the drop-down

(defn valid-choice
  "Keep size and milk if the drink allows them (and the milk is in stock). Otherwise take the
  first one that is allowed. Milk is nil for a drink without milk, or when all its milks are out."
  [drink sold-out {:keys [size milk]}]
  (let [{:keys [sizes milks]} (menu drink)
        in-stock (remove sold-out milks)]
    {:size (if (some #{size} sizes) size (first sizes))
     :milk (if (some #{milk} in-stock) milk (first in-stock))}))

(defn drink-options-view
  "The #drink-options element: a size select and a milk select for this drink. The option
  lists exist only here, as HTML from the server."
  [drink sold-out {:keys [size milk]}]
  ;; EXERCISE(drink-options-view): Return hiccup for div#drink-options. It holds
  ;; select#size (data-bind:size) with one option per size of the drink, and select#milk
  ;; (data-bind:milk) with one option per milk, or [:p.muted "No milk"] when the drink takes no
  ;; milk. Mark the chosen size and milk as selected. A sold-out milk is disabled and its label
  ;; ends with " (sold out)".
  (let [{:keys [sizes milks]} (menu drink)]
    [:div#drink-options
     [:label "Size "
      [:select#size {:data-bind:size ""}
       (for [s sizes]
         [:option (cond-> {:value (name s)} (= s size) (assoc :selected "")) (label s)])]]
     (if (seq milks)
       [:label " Milk "
        [:select#milk {:data-bind:milk ""}
         (for [m milks]
           [:option (cond-> {:value (name m)}
                      (= m milk)   (assoc :selected "")
                      (sold-out m) (assoc :disabled ""))
            (cond-> (label m) (sold-out m) (str " (sold out)"))])]]
       [:p.muted "No milk"])])
  ;; END EXERCISE
  )

(defn drink-options-response
  "Answer a change of drink: new option lists, and new size/milk signals where the old ones
  no longer fit."
  [signals sold-out]
  ;; EXERCISE(drink-options-response): Read drink, size and milk from the signals. Find the
  ;; valid choice. Send two events: first patch #drink-options with the view in replace mode,
  ;; then patch only the signals that changed (size, milk). A missing milk is sent as "" so the
  ;; bound select keeps a string.
  (let [{:keys [drink size milk]} (->draft signals)
        choice  (valid-choice drink sold-out {:size size :milk milk})
        changed (cond-> {}
                  (not= size (:size choice)) (assoc :size (name (:size choice)))
                  (not= milk (:milk choice)) (assoc :milk (or (some-> (:milk choice) name) "")))]
    (sse/sse-response
     ;; Replace, not morph: a morph keeps the user's selected option by position (see post 4).
     (sse/patch-elements (str (h/html (drink-options-view drink sold-out choice))) {:mode :replace})
     (sse/patch-signals changed {})))
  ;; END EXERCISE
  )

;; ---------------------------------------------------------------- the cart (server state)

(defn add-to-cart
  "Add the draft to the cart of session sid. Return the new state, or nil when the draft is
  not valid (sold-out milks count)."
  [state sid draft]
  ;; EXERCISE(add-to-cart): Return nil when draft-error finds a problem (use the state's
  ;; sold-out set). Otherwise add the line {:drink :size :milk :qty} to the end of the cart for
  ;; sid and return the new state.
  (when-not (draft-error draft (:sold-out state))
    (update-in state [:carts sid] (fnil conj []) (select-keys draft [:drink :size :milk :qty])))
  ;; END EXERCISE
  )

(defn line-text [{:keys [drink size milk qty] :as line}]
  (str qty " × " (label size) (when milk (str " " (label milk))) " " (get-in menu [drink :name])
       " — " (dollars (* qty (price-cents line)))))

(defn error-view
  "The #draft-error element: the server's reason the draft was refused, or empty."
  [message]
  [:p#draft-error.error message])

(defn cart-view [lines]
  [:div#cart
   [:h2 "Cart"]
   (if (seq lines)
     (list [:ul (for [line lines] [:li (line-text line)])]
           [:p "Total: " [:strong (dollars (reduce + (map #(* (:qty %) (price-cents %)) lines)))]])
     [:p.muted "Your cart is empty."])])

;; ---------------------------------------------------------------- state + handlers

(defonce state (atom {:sold-out #{} :carts {}}))

(defn page [{:keys [sold-out carts]} sid]
  (let [choice (valid-choice :latte sold-out {})]
    (str "<!DOCTYPE html>"
         (h/html
          [:html
           [:head [:meta {:charset "utf-8"}] [:title "Part 4: when to use a signal"]
            [:script {:type "module" :src datastar-cdn}]
            [:style ".muted{color:#777} .error{color:#b00}"]]
           [:body
            [:div#app {:data-signals (json/generate-string {:drink    "latte"
                                                            :size     (name (:size choice))
                                                            :milk     (or (some-> (:milk choice) name) "")
                                                            :qty      1
                                                            :_loading false})}
             [:h1 "Brew Board"]
             [:div#draft
              [:label "Drink "
               [:select#drink {:data-bind:drink "" :data-on:change "@get('/drink-options')"}
                (for [[k {:keys [name]}] menu]
                  [:option (cond-> {:value (clojure.core/name k)} (= k :latte) (assoc :selected "")) name])]]
              (drink-options-view :latte sold-out choice)
              [:label " Qty " [:input {:type "number" :min 1 :max 10 :data-bind:qty ""}]]
              " "
              [:button {:data-on:click "@post('/cart/items')" :data-indicator:_loading ""
                        :data-attr:disabled "$_loading"}
               "Add to cart"]
              (error-view nil)]
             (cart-view (get carts sid))]]]))))

(defn- sid-of [req] (get-in req [:cookies "sid" :value]))

(defn page-response [req]
  (let [sid (or (sid-of req) (str (UUID/randomUUID)))]
    (cond-> (sse/html-response (page @state sid))
      (not (sid-of req)) (assoc :cookies {"sid" {:value sid :path "/" :http-only true :same-site :lax}}))))

(defn add-item-response
  "POST /cart/items: add the draft from the signals to this session's cart."
  [req]
  (let [sid   (sid-of req)
        draft (->draft (sse/read-signals req))]
    (if-let [err (if sid (draft-error draft (:sold-out @state)) "No session. Reload the page.")]
      (sse/sse-response (sse/patch-elements (str (h/html (error-view err))) {}))
      (let [new-state (swap! state #(or (add-to-cart % sid draft) %))]
        (sse/sse-response
         (sse/patch-elements (str (h/html (cart-view (get-in new-state [:carts sid])))) {})
         (sse/patch-elements (str (h/html (error-view nil))) {}))))))

(defn routes [{:keys [request-method uri] :as req}]
  (case [request-method uri]
    [:get "/"]              (page-response req)
    [:get "/drink-options"] (drink-options-response (sse/read-signals req) (:sold-out @state))
    [:post "/cart/items"]   (add-item-response req)
    {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"}))

(def handler (-> #'routes wrap-params wrap-cookies))

(defonce server (atom nil))

(defn stop! []
  (when-let [s @server]
    (hk/server-stop! s)
    (reset! server nil)))

(defn start!
  "Start the server. Port 0 lets the OS pick a free port. Return the port."
  [port]
  (stop!)
  (->> (hk/run-server #'handler {:port port :legacy-return-value? false})
       (reset! server)
       hk/server-port))

;; ---------------------------------------------------------------- demo

(defn- send! ^HttpResponse [^HttpRequest req]
  (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString)))

(defn- datastar-get [url cookie signals]
  (println (str "→ GET " (subs url (string/index-of url "/" 8)) "  signals " (json/generate-string signals)))
  (.body (send! (-> (HttpRequest/newBuilder
                     (URI. (str url "?datastar=" (codec/url-encode (json/generate-string signals)))))
                    (.header "Datastar-Request" "true")
                    (.header "Cookie" cookie)
                    .build))))

(defn- datastar-post [url cookie signals]
  (println (str "→ POST " (subs url (string/index-of url "/" 8)) "  body " (json/generate-string signals)))
  (.body (send! (-> (HttpRequest/newBuilder (URI. url))
                    (.header "Datastar-Request" "true")
                    (.header "Content-Type" "application/json")
                    (.header "Cookie" cookie)
                    (.POST (HttpRequest$BodyPublishers/ofString (json/generate-string signals)))
                    .build))))

(defn demo!
  "Run the narrated demo: start a server, make real requests, print what happens, stop."
  []
  (reset! state {:sold-out #{} :carts {}})
  (let [port   (start! 0)
        url    (str "http://localhost:" port)
        resp   (send! (.build (HttpRequest/newBuilder (URI. (str url "/")))))
        cookie (-> resp .headers (.firstValue "set-cookie") (.orElse "") (string/split #";") first)]
    (println "Part 4: when to use a signal. Server on" url)
    (println "\n── GET /  (the first visit gets a session cookie)")
    (println "Set-Cookie:" cookie)
    (println "\n── Ada switches the drink to Espresso. data-on:change sends the signals:")
    (print (datastar-get (str url "/drink-options") cookie {:drink "espresso" :size "small" :milk "whole" :qty 1}))
    (println "Espresso has no small and takes no milk, so both signals change. The option lists")
    (println "arrive as HTML; no signal ever holds the list of sizes.")
    (println "\n── Sam marks oat milk sold out. Grace switches to Latte with oat:")
    (swap! state update :sold-out conj :oat)
    (print (datastar-get (str url "/drink-options") cookie {:drink "latte" :size "medium" :milk "oat" :qty 1}))
    (println "Medium is still fine, so only milk changes. Oat stays in the list, disabled.")
    (println "\n── Ada adds two medium whole-milk lattes")
    (print (datastar-post (str url "/cart/items") cookie {:drink "latte" :size "medium" :milk "whole" :qty 2}))
    (println "\n── A stale page still says oat (it loaded before Sam's change):")
    (print (datastar-post (str url "/cart/items") cookie {:drink "latte" :size "medium" :milk "oat" :qty 1}))
    (println "The server checks stock on every add. The cart is still two lattes:")
    (println " " (pr-str (get-in @state [:carts (subs cookie 4)])))
    (println "\nTo try it in a browser: (lesson04.where-state-lives/start! 8080), then open http://localhost:8080/")
    (println "Mark a milk sold out at the REPL: (swap! lesson04.where-state-lives/state update :sold-out conj :oat)")
    (stop!)))

(defn -main [& _]
  (demo!)
  (shutdown-agents))
