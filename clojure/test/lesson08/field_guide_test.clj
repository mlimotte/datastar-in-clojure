(ns lesson08.field-guide-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [lesson08.field-guide :as fg]
            [lesson08.sse :as sse]))

(defn- req
  ([method uri] (req method uri {}))
  ([method uri {:keys [query headers]}]
   {:request-method method :uri uri :query-string query
    :headers (merge {"datastar-request" "true"} headers)}))

(deftest same-read-two-transports
  (testing "text/html carries the element as the body"
    (let [r (fg/read-as-html {})]
      (is (= "text/html; charset=utf-8" (get-in r [:headers "Content-Type"])))
      (is (string/starts-with? (:body r) "<ul id=\"menu-card\">"))))
  (testing "SSE carries the same element in one event"
    (let [r (fg/read-as-sse {})]
      (is (= "text/event-stream" (get-in r [:headers "Content-Type"])))
      (is (string/starts-with? (:body r) "event: datastar-patch-elements\ndata: elements <ul id=\"menu-card\">"))))
  (testing "text/html patch options travel in datastar-* headers"
    (let [r (fg/append-as-html {})]
      (is (= "#menu-card" (get-in r [:headers "datastar-selector"])))
      (is (= "append" (get-in r [:headers "datastar-mode"]))))))

(deftest whole-view-vs-targeted
  (let [shown (mapv :id (fg/sorted-orders fg/initial-orders))
        after (fg/advance fg/initial-orders 3)]
    (is (= [1 2 3 4] shown))
    (testing "a whole-view render re-sorts: #3 moves up"
      (is (= [1 3 2 4] (fg/row-ids shown after :whole))))
    (testing "a targeted patch keeps the position, so the sort is stale"
      (is (= [1 2 3 4] (fg/row-ids shown after :targeted))))
    (testing "the targeted patch carries only the changed row"
      (let [p (fg/targeted-patch after 3)]
        (is (string/includes? p "id=\"order-3\""))
        (is (not (string/includes? p "order-2")))
        (is (< (fg/byte-count p) (fg/byte-count (fg/whole-view-patch after))))))))

(deftest search-and-latency
  (is (= ["Latte" "Chai Latte"] (map :name (fg/search "lat"))))
  (is (> (fg/latency-ms "la") (fg/latency-ms "lat")))
  (is (= 30 (fg/latency-ms "a long query"))))

(deftest sequence-guard
  (let [latest (-> {} (fg/note-query "t1" 1) (fg/note-query "t1" 2))]
    (is (fg/stale? latest "t1" 1))
    (is (not (fg/stale? latest "t1" 2)))
    (testing "tabs don't interfere"
      (is (not (fg/stale? latest "t2" 1))))
    (testing "a late arrival of an old seq doesn't lower the latest"
      (is (= 2 (get (fg/note-query latest "t1" 1) "t1"))))))

(deftest receipts-markup
  (let [html (fg/html-str (fg/receipts))]
    (testing "neither details is rendered open; one names open in data-preserve-attr"
      (is (not (re-find #"<details[^>]* open" html)))
      (is (re-find #"<details data-preserve-attr=\"open\" id=\"receipt-kept\">" html)))))

(deftest read-signals-never-throws
  (is (= {} (sse/read-signals {:request-method :get :query-params {"datastar" "{not json"}})))
  (is (= {} (sse/read-signals {:request-method :get :query-params {"datastar" "[1,2]"}})))
  (is (= {:q "la"} (sse/read-signals {:request-method :get :query-params {"datastar" "{\"q\":\"la\"}"}})))
  (is (= {} (sse/read-signals {:request-method :post :body "42"}))))

(deftest routes
  (testing "commands need the Datastar-Request header"
    (is (= 403 (:status (fg/handler (req :post "/reset" {:headers {"datastar-request" nil}}))))))
  (testing "advance answers with the patch for its mode"
    (reset! fg/!orders fg/initial-orders)
    (let [r (fg/handler (req :post "/advance/3/targeted"))]
      (is (= 200 (:status r)))
      (is (string/includes? (:body r) "id=\"order-3\""))
      (is (not (string/includes? (:body r) "id=\"board\""))))
    (reset! fg/!orders fg/initial-orders)
    (is (string/includes? (:body (fg/handler (req :post "/advance/3/whole"))) "id=\"board\"")))
  (testing "the sequence guard answers 204 to an overtaken query"
    (reset! fg/latest-seq {"t9" 5})
    (let [signals (java.net.URLEncoder/encode "{\"tabid\":\"t9\",\"seq\":4}" "UTF-8")]
      (is (= 204 (:status (fg/handler (req :get "/search-seq" {:query (str "q=la&datastar=" signals)}))))))))
