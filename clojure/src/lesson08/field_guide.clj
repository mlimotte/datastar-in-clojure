(ns lesson08.field-guide
  "Part 8: a field guide. No exercises; a demo that puts three trade-offs side by side and prints
  the bytes on the wire.
  (a) The same read answered as text/html and as an SSE stream.
  (b) One change sent as a whole-view re-render and as a targeted patch: bytes, and where the row ends up.
  (c) Search requests whose responses arrive out of order, and two fixes: one URL that Datastar
      cancels, and a sequence number the server checks."
  (:require [clojure.string :as string]
            [cheshire.core :as json]
            [hiccup2.core :as h]
            [lesson08.sse :as sse]
            [org.httpkit.server :as hk]
            [reitit.ring :as ring]
            [ring.middleware.params :refer [wrap-params]])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Version HttpRequest HttpResponse
                          HttpResponse$BodyHandlers)))

(def datastar-cdn
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.4/bundles/datastar.js")

(defn html-str [hiccup] (str (h/html hiccup)))

(defn byte-count [s] (count (.getBytes ^String s "UTF-8")))

;; ---------------------------------------------------------------------------
;; (a) One read, two transports

(def menu
  [{:id :espresso :name "Espresso"} {:id :americano :name "Americano"}
   {:id :latte :name "Latte"} {:id :cappuccino :name "Cappuccino"}
   {:id :mocha :name "Mocha"} {:id :chai :name "Chai Latte"}])

(defn menu-card
  "The menu as one id'd element. The same function serves the page and both read routes."
  []
  [:ul#menu-card (for [{:keys [id] label :name} menu] [:li {:id (str "menu-" (name id))} label])])

(defn read-as-html [_] (sse/html-response (html-str (menu-card))))

(defn read-as-sse [_] (sse/sse-response (sse/patch-elements (html-str (menu-card)))))

(defn append-as-html
  "A text/html response that appends instead of morphing: the patch options travel in headers."
  [_]
  (sse/html-response (html-str [:li "Seasonal: Pumpkin Latte"])
                     {"datastar-selector" "#menu-card" "datastar-mode" "append"}))

;; ---------------------------------------------------------------------------
;; (b) Whole view vs targeted patch

(def initial-orders
  [{:id 1 :customer "Ada"   :drink "Latte"      :status :making}
   {:id 2 :customer "Grace" :drink "Mocha"      :status :placed}
   {:id 3 :customer "Linus" :drink "Chai Latte" :status :placed}
   {:id 4 :customer "Ada"   :drink "Espresso"   :status :placed}])

(def status-rank {:ready 0 :making 1 :placed 2})

(defn sorted-orders
  "Board order: ready first, then making, then placed; oldest first inside a status."
  [orders]
  (sort-by (juxt (comp status-rank :status) :id) orders))

(defn advance
  "Move one order to its next status."
  [orders id]
  (mapv #(cond-> % (= id (:id %)) (update :status {:placed :making :making :ready :ready :ready}))
        orders))

(defn order-row [{:keys [id customer drink status]}]
  [:li {:id (str "order-" id) :class (name status)}
   (str "#" id " " customer ", " drink " (" (name status) ")")])

(defn board
  "The whole board. The page, the whole-view patch and the reset all render through this."
  [orders]
  [:ul#board (map order-row (sorted-orders orders))])

(defn whole-view-patch [orders] (sse/patch-elements (html-str (board orders))))

(defn targeted-patch
  "Only the changed row. It keeps its old position, so the board's sort is now stale."
  [orders id]
  (sse/patch-elements (html-str (order-row (first (filter #(= id (:id %)) orders))))))

(defn row-ids
  "Order ids top to bottom after applying a patch to `shown` (ids as currently displayed).
  A whole-view patch re-sorts; a targeted patch replaces the row in place."
  [shown orders mode]
  (case mode
    :whole    (mapv :id (sorted-orders orders))
    :targeted (vec shown)))

;; ---------------------------------------------------------------------------
;; Native HTML inside a re-rendered region

(defn receipts
  "Two native <details>. The browser sets their `open` attribute when the user opens them; the
  server never renders it, so a morph removes it unless data-preserve-attr names it."
  []
  [:div#receipts
   [:details#receipt-kept {:data-preserve-attr "open"}
    [:summary "Receipt (data-preserve-attr=\"open\")"] [:p "Medium oat latte, $5.60"]]
   [:details#receipt-lost
    [:summary "Receipt (no preserve)"] [:p "Medium oat latte, $5.60"]]])

(defn rerender-receipts [_] (sse/sse-response (sse/patch-elements (html-str (receipts)))))

;; ---------------------------------------------------------------------------
;; (c) Out-of-order responses

(defn search
  "Drinks whose name contains q, case-insensitive."
  [q]
  (filter #(string/includes? (string/lower-case (:name %)) (string/lower-case (or q ""))) menu))

(defn latency-ms
  "Toy latency: short queries match more and take longer. \"la\" is slow, \"lat\" is fast."
  [q]
  (max 30 (- 450 (* 100 (count q)))))

(defn results-view [q]
  [:div#results
   [:p.muted (str "Results for \"" q "\"")]
   [:ul (for [{:keys [name]} (search q)] [:li name])]])

(defonce latest-seq (atom {}))

(defn note-query
  "Record the newest sequence number seen for a tab."
  [latest tabid seq]
  (update latest tabid (fnil max 0) seq))

(defn stale?
  "True when a newer query from the same tab reached the server first."
  [latest tabid seq]
  (< seq (get latest tabid 0)))

(defn search-by-url
  "GET /search-url?q=… : a different URL per query, so Datastar does not cancel older ones."
  [{:keys [query-params]}]
  (let [q (get query-params "q" "")]
    (Thread/sleep (long (latency-ms q)))
    (sse/sse-response (sse/patch-elements (html-str (results-view q))))))

(defn search-by-signal
  "GET /search : one URL, the query rides in signals. A newer request from the same element
  aborts the older one in the browser."
  [req]
  (let [q (:q (sse/read-signals req) "")]
    (Thread/sleep (long (latency-ms q)))
    (sse/sse-response (sse/patch-elements (html-str (results-view q))))))

(defn search-guarded
  "GET /search-seq?q=… with signals tabid and seq: the server answers 204 to a query that a
  newer one from the same tab has overtaken."
  [{:keys [query-params] :as req}]
  (let [{:keys [tabid seq]} (sse/read-signals req)
        q (get query-params "q" "")]
    (swap! latest-seq note-query tabid seq)
    (Thread/sleep (long (latency-ms q)))
    (if (stale? @latest-seq tabid seq)
      {:status 204 :headers {} :body ""}
      (sse/sse-response (sse/patch-elements (html-str (results-view q)))))))

;; ---------------------------------------------------------------------------
;; Page and server

(defonce !orders (atom initial-orders))

(defn wrap-datastar-request
  "Commands answer 403 unless the request carries Datastar-Request: true (see part 5)."
  [handler]
  (fn [req]
    (if (= "true" (get-in req [:headers "datastar-request"]))
      (handler req)
      {:status 403 :headers {"Content-Type" "text/plain"} :body "Datastar requests only"})))

(defn page []
  (str "<!DOCTYPE html>"
       (h/html
        [:html
         [:head [:meta {:charset "utf-8"}] [:title "Part 8: field guide"]
          [:script {:type "module" :src datastar-cdn}]
          [:style "body{font:16px system-ui;max-width:46rem;margin:2rem auto;padding:0 1rem}
                   .muted{color:#777} li.making{color:#b45309} li.ready{color:#15803d}
                   section{border:1px solid #ddd;border-radius:8px;padding:.6rem 1rem;margin:1rem 0}"]]
         [:body {:data-signals (json/generate-string {:q "" :qurl "" :qseq "" :seq 0})
                 :data-signals:tabid__ifmissing "crypto.randomUUID()"}
          [:h1 "Field guide demos"]
          [:section
           [:h2 "(a) one read, two transports"]
           (menu-card)
           [:button {:data-on:click "@get('/read/html')"} "Read as text/html"] " "
           [:button {:data-on:click "@get('/read/sse')"} "Read as SSE"] " "
           [:button {:data-on:click "@get('/read/append')"} "Append via headers"]]
          [:section
           [:h2 "(b) whole view vs targeted patch"]
           (board @!orders)
           [:button {:data-on:click "@post('/advance/3/whole')"} "Advance #3, whole view"] " "
           [:button {:data-on:click "@post('/advance/3/targeted')"} "Advance #3, targeted"] " "
           [:button {:data-on:click "@post('/reset')"} "Reset"]]
          [:section
           [:h2 "(c) search: type fast"]
           [:p [:label "Different URL per query "
                [:input {:data-bind:qurl "" :data-on:input "@get('/search-url?q=' + encodeURIComponent($qurl))"}]]]
           [:p [:label "One URL, query in signals "
                [:input {:data-bind:q "" :data-on:input "@get('/search')"}]]]
           [:p [:label "Different URLs + sequence number "
                [:input {:data-bind:qseq ""
                         :data-on:input "$seq = $seq + 1; @get('/search-seq?q=' + encodeURIComponent($qseq))"}]]]
           (results-view "")]
          [:section
           [:h2 "(d) native <details> inside a re-rendered region"]
           (receipts)
           [:button {:data-on:click "@get('/receipts')"} "Re-render receipts"]]]])))

(def app
  (ring/ring-handler
   (ring/router
    [["/" {:get (fn [_] (sse/html-response (page)))}]
     ["/read/html" {:get read-as-html}]
     ["/read/sse" {:get read-as-sse}]
     ["/read/append" {:get append-as-html}]
     ["/search-url" {:get search-by-url}]
     ["/search" {:get search-by-signal}]
     ["/search-seq" {:get search-guarded}]
     ["/receipts" {:get rerender-receipts}]
     ["" {:middleware [wrap-datastar-request]}
      ["/advance/:id/:mode"
       {:post (fn [{{:keys [id mode]} :path-params}]
                (let [id (parse-long id)
                      orders (swap! !orders advance id)]
                  (sse/sse-response (if (= mode "whole") (whole-view-patch orders) (targeted-patch orders id)))))}]
      ["/reset" {:post (fn [_] (sse/sse-response (whole-view-patch (reset! !orders initial-orders))))}]]])
   (constantly {:status 404 :headers {} :body "not found"})))

(def handler (wrap-params #'app))

(defonce server (atom nil))

(defn stop! []
  (when-let [s @server]
    (hk/server-stop! s)
    (reset! server nil)))

(defn start!
  "Start the server. Port 0 lets the OS pick a free port. Return the port."
  [port]
  (stop!)
  (reset! !orders initial-orders)
  (reset! latest-seq {})
  (->> (hk/run-server #'handler {:port port :legacy-return-value? false})
       (reset! server)
       hk/server-port))

;; ---------------------------------------------------------------------------
;; Demo

(def client (-> (HttpClient/newBuilder) (.version HttpClient$Version/HTTP_1_1) .build))

(defn- get-request [base path signals]
  (-> (HttpRequest/newBuilder
       (URI. (str base path
                  (when signals
                    (str (if (string/includes? path "?") "&" "?") "datastar="
                         (URLEncoder/encode (json/generate-string signals) "UTF-8"))))))
      (.header "Datastar-Request" "true")
      .GET .build))

(defn- show-response
  "Print status, the headers Datastar looks at, and the body bytes."
  [^HttpResponse resp]
  (let [hs   (.map (.headers resp))
        pick (fn [k] (some-> (get hs k) first))]
    (println "  " (.statusCode resp) (pick "content-type"))
    (doseq [k ["datastar-selector" "datastar-mode"] :let [v (pick k)] :when v]
      (println "  " (str k ": " v)))
    (println (string/join "\n" (map #(str "   │ " %) (string/split-lines (str (.body resp))))))
    (println "   body:" (byte-count (str (.body resp))) "bytes")))

(defn- demo-reads [base]
  (println "\n── (a) The same read, answered two ways. Datastar applies both the same way.")
  (doseq [path ["/read/html" "/read/sse" "/read/append"]]
    (println (str "\nGET " path))
    (show-response (.send client (get-request base path {}) (HttpResponse$BodyHandlers/ofString)))))

(defn- demo-patches []
  (println "\n── (b) Sam advances #3 (Linus, Chai Latte) from placed to making.")
  (let [shown  (mapv :id (sorted-orders initial-orders))
        after  (advance initial-orders 3)
        whole  (whole-view-patch after)
        target (targeted-patch after 3)]
    (println "   board before, top to bottom:" shown)
    (println "   whole view :" (byte-count whole) "bytes; rows now" (row-ids shown after :whole)
             "(#3 moved up under the barista's cursor)")
    (println "   targeted   :" (byte-count target) "bytes; rows now" (row-ids shown after :targeted)
             "(#3 stays put, but the board is no longer sorted by status)")
    (print (string/join "\n" (map #(str "   │ " %) (string/split-lines target))))
    (println)))

(defn- timed-async
  "Send a GET and return a future of [label arrival-ms status body]."
  [base path signals label t0]
  (-> (.sendAsync client (get-request base path signals) (HttpResponse$BodyHandlers/ofString))
      (.thenApply (fn [^HttpResponse r]
                    [label (- (System/currentTimeMillis) t0) (.statusCode r) (.body r)]))))

(defn- results-of [body]
  (or (second (re-find #"Results for &quot;([^&]*)&quot;" (str body)))
      (second (re-find #"Results for \"([^\"]*)\"" (str body)))))

(defn- print-arrivals [arrivals]
  (doseq [[label ms status body] (sort-by second arrivals)]
    (println (format "   %4d ms  %-28s → %s" ms label
                     (if (= 204 status) "204, nothing to apply" (str "results for \"" (results-of body) "\""))))))

(defn- demo-ordering [base]
  (println "\n── (c) Ada types \"la\" then, 30 ms later, \"lat\". \"la\" is the slow query.")
  (println "   Different URL per query: both requests run; the browser applies each as it arrives.")
  (let [t0 (System/currentTimeMillis)
        f1 (timed-async base "/search-url?q=la" {} "GET /search-url?q=la" t0)
        _  (Thread/sleep 30)
        f2 (timed-async base "/search-url?q=lat" {} "GET /search-url?q=lat" t0)
        arrivals [@f1 @f2]]
    (print-arrivals arrivals)
    (println (str "   last applied: \"" (results-of (last (last (sort-by second arrivals))))
                  "\". The page shows results for a query Ada already typed past.")))
  (println "\n   One URL, query in signals: Datastar keeps one AbortController per method and URL,")
  (println "   so the browser aborts the \"la\" request when the \"lat\" one starts. The server still")
  (println "   does the work, but nothing late reaches the page. (A browser-only fix; not shown here.)")
  (println "\n   Different URLs + a sequence number the server checks (tab \"t1\"):")
  (let [t0 (System/currentTimeMillis)
        f1 (timed-async base "/search-seq?q=la" {:tabid "t1" :seq 1} "GET /search-seq?q=la  seq 1" t0)
        _  (Thread/sleep 30)
        f2 (timed-async base "/search-seq?q=lat" {:tabid "t1" :seq 2} "GET /search-seq?q=lat seq 2" t0)]
    (print-arrivals [@f1 @f2])
    (println "   The late answer is a 204, which Datastar ignores, so \"lat\" stays on the page.")))

(defn demo!
  "Run the narrated demo: start a server, make real requests, print what happens, stop."
  []
  (let [port (start! 0)
        base (str "http://localhost:" port)]
    (println "Part 8: field guide. Server on" base)
    (demo-reads base)
    (demo-patches)
    (demo-ordering base)
    (println "\nTo try it in a browser: (lesson08.field-guide/start! 8080), then open http://localhost:8080/")
    (stop!)))

(defn -main [& _]
  (demo!)
  (shutdown-agents))
