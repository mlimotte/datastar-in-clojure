(ns lesson03.signals
  "Part 3: signals.
  The drink draft lives in signals: data-signals declares them, data-bind ties them to inputs,
  data-computed derives an estimate in the browser, data-text / data-show / data-class /
  data-attr render them, and data-indicator tracks the request. GET /quote reads the signals
  on the server and patches back the price the server trusts."
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [hiccup2.core :as h]
            [lesson03.sse :as sse]
            [org.httpkit.server :as hk]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.codec :as codec])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def datastar-cdn
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.4/bundles/datastar.js")

;; ---------------------------------------------------------------- menu

(def menu
  (array-map
   :espresso   {:name "Espresso"   :base 300 :sizes [:single :double]        :milks []}
   :americano  {:name "Americano"  :base 350 :sizes [:small :large]          :milks []}
   :latte      {:name "Latte"      :base 450 :sizes [:small :medium :large]  :milks [:whole :oat :almond]}
   :cappuccino {:name "Cappuccino" :base 425 :sizes [:small :medium]         :milks [:whole :oat]}
   :mocha      {:name "Mocha"      :base 500 :sizes [:small :medium :large]  :milks [:whole :oat :almond]}
   :chai       {:name "Chai Latte" :base 450 :sizes [:medium :large]         :milks [:whole :oat :almond]}))

(def size-delta (array-map :single 0 :double 100 :small 0 :medium 50 :large 100))
(def milk-delta (array-map :whole 0 :oat 60 :almond 60))

(defn price-cents
  "The price of one drink in cents. A drink without milk has :milk nil."
  [{:keys [drink size milk]}]
  ;; EXERCISE(price-cents): Add the drink's base price, the size delta, and the milk delta
  ;; (no milk adds nothing).
  (+ (get-in menu [drink :base])
     (size-delta size)
     (get milk-delta milk 0))
  ;; END EXERCISE
  )

(defn ->draft
  "Turn signals (strings from the browser) into a draft with keywords."
  [{:keys [drink size milk qty]}]
  {:drink (some-> drink not-empty keyword)
   :size  (some-> size not-empty keyword)
   :milk  (some-> milk not-empty keyword)
   :qty   qty})

(defn draft-error
  "Return why the draft is not a valid order line, or nil when it is valid."
  [{:keys [drink size milk qty]}]
  (let [{:keys [name sizes milks]} (menu drink)]
    (cond
      (nil? name)                      "Pick a drink."
      (not (some #{size} sizes))       (str name " does not come in that size.")
      (and (seq milks) (nil? milk))    (str name " needs a milk.")
      (and milk (not (some #{milk} milks))) (str name " does not take that milk.")
      (not (and (int? qty) (<= 1 qty 10))) "Quantity must be 1 to 10.")))

;; ---------------------------------------------------------------- reading signals

(defn read-signals
  "Read the signals Datastar sent. GET and DELETE send them as JSON in the datastar query
  parameter; other methods send a JSON body. Return a map with keyword keys."
  [{:keys [request-method query-params body]}]
  ;; EXERCISE(read-signals): Take the JSON text from the "datastar" query parameter for GET and
  ;; DELETE, and from the request body for other methods. Parse it with keyword keys. Return {}
  ;; when there is nothing to read.
  (let [raw (if (#{:get :delete} request-method)
              (get query-params "datastar")
              (some-> body slurp))]
    (if (string/blank? raw) {} (json/parse-string raw true)))
  ;; END EXERCISE
  )

(defn quote-response
  "Answer the browser's signals with the server's quote: patch the quote and error signals."
  [signals]
  ;; EXERCISE(quote-response): Make a draft from the signals. If it has an error, patch
  ;; {quote: 0, error: <message>}. Otherwise patch {quote: price × qty, error: ""}. Return a
  ;; one-shot SSE response.
  (let [draft (->draft signals)]
    (sse/sse-response
     (sse/patch-signals (if-let [err (draft-error draft)]
                          {:quote 0 :error err}
                          {:quote (* (price-cents draft) (:qty draft)) :error ""})
                        {})))
  ;; END EXERCISE
  )

;; ---------------------------------------------------------------- page

(def initial-signals
  {:drink "latte" :size "medium" :milk "oat" :qty 1 :quote 0 :error ""
   ;; Underscore: the browser keeps these, @get never sends them.
   :_loading false
   :_prices {:base (update-vals menu :base) :size size-delta :milk milk-delta}})

(defn- options [pairs]
  (for [[value label] pairs] [:option {:value value} label]))

(def estimate-expr
  "($_prices.base[$drink] + ($_prices.size[$size] || 0) + ($_prices.milk[$milk] || 0)) * $qty")

(defn page []
  (str "<!DOCTYPE html>"
       (h/html
        [:html
         [:head [:meta {:charset "utf-8"}] [:title "Part 3: signals"]
          [:script {:type "module" :src datastar-cdn}]
          [:style ".busy{opacity:.5} .error{color:#b00} pre{background:#f4f4f4;padding:.5em}"]]
         [:body
          [:h1 "Signals"]
          ;; Hiccup sorts attributes and Datastar applies them in source order, so the
          ;; signals go on an outer element: they must exist before data-computed reads them.
          [:div#app {:data-signals (json/generate-string initial-signals)}
           [:div#draft {:data-computed:estimate estimate-expr
                        :data-class:busy        "$_loading"}
            [:label "Drink " [:select {:data-bind:drink ""}
                              (options (for [[k {:keys [name]}] menu] [(clojure.core/name k) name]))]]
            [:label " Size " [:select {:data-bind:size ""}
                              (options (for [k (keys size-delta)] [(name k) (name k)]))]]
            [:label " Milk " [:select {:data-bind:milk ""}
                              (options (cons ["" "no milk"] (for [k (keys milk-delta)] [(name k) (name k)])))]]
            [:label " Qty " [:input {:type "number" :min 1 :max 10 :data-bind:qty ""}]]
            [:p "Browser estimate: " [:span {:data-text "'$' + ($estimate / 100).toFixed(2)"}]]
            [:button {:data-on:click        "@get('/quote')"
                      :data-indicator:_loading ""
                      :data-attr:disabled   "$_loading"}
             "Ask the server"]
            [:p {:data-show "$quote > 0"}
             "Server quote: " [:strong {:data-text "'$' + ($quote / 100).toFixed(2)"}]]
            [:p.error {:data-show "$error != ''" :data-text "$error"}]
            [:h3 "Every signal in the browser"]
            [:pre {:data-json-signals ""}]
            [:h3 "What @get('/quote') sends (no _ signals)"]
            [:pre {:data-json-signals "{exclude: /(^|\\.)_/}"}]]]]])))

;; ---------------------------------------------------------------- server

(defn routes [{:keys [request-method uri] :as req}]
  (case [request-method uri]
    [:get "/"]       (sse/html-response (page))
    [:get "/quote"]  (quote-response (read-signals req))
    [:post "/quote"] (quote-response (read-signals req))
    {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"}))

(def handler (wrap-params #'routes))

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

(defn- send! [^HttpRequest req]
  (.body (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))))

(defn- datastar-get
  "GET the way Datastar does it: signals as JSON in the datastar query parameter."
  [url signals]
  (let [full (str url "?datastar=" (codec/url-encode (json/generate-string signals)))]
    (println (str "→ GET " full))
    (send! (-> (HttpRequest/newBuilder (URI. full))
               (.header "Datastar-Request" "true")
               .build))))

(defn- datastar-post
  "POST the way Datastar does it: signals as a JSON body."
  [url signals]
  (let [body (json/generate-string signals)]
    (println (str "→ POST " url "  body: " body))
    (send! (-> (HttpRequest/newBuilder (URI. url))
               (.header "Datastar-Request" "true")
               (.header "Content-Type" "application/json")
               (.POST (HttpRequest$BodyPublishers/ofString body))
               .build))))

(defn demo!
  "Run the narrated demo: start a server, make real requests, print what happens, stop."
  []
  (let [port (start! 0)
        url  (str "http://localhost:" port "/quote")
        sent (dissoc initial-signals :_prices :_loading)]
    (println "Part 3: signals. Server on" (str "http://localhost:" port))
    (println "\nThe page declares these signals with data-signals:")
    (println " " (json/generate-string initial-signals))
    (println "_prices starts with _, so the default filter /(^|\\.)_/ keeps it in the browser.")
    (println "\n── A medium oat latte, two of them")
    (print (datastar-get url (assoc sent :qty 2)))
    (println "The browser merges {quote, error} into its signals; data-text and data-show update.")
    (println "\n── An espresso with oat milk (the static selects allow it)")
    (print (datastar-get url (assoc sent :drink "espresso" :size "double")))
    (println "The browser estimate would still show a price. Only the server knows the rule.")
    (println "\n── The same signals as a POST body")
    (print (datastar-post url (assoc sent :drink "chai" :size "large" :milk "almond")))
    (println "Chai 450 + large 100 + almond 60 = 610 cents.")
    (println "\nTo try it in a browser: (lesson03.signals/start! 8080), then open http://localhost:8080/")
    (stop!)))

(defn -main [& _]
  (demo!)
  (shutdown-agents))
