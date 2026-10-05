(ns brewboard.routes-test
  (:require [brewboard.main :as main]
            [brewboard.routes :as routes]
            [brewboard.state :as state]
            [brewboard.views :as views]
            [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io ByteArrayInputStream)
           (java.net URLEncoder)))

(defn- req [method uri & {:keys [signals datastar? sid ua]}]
  (let [js (some-> signals json/generate-string)
        get? (#{:get :delete} method)]
    (cond-> {:request-method method :uri uri :headers {}}
      (and js get?)       (assoc :query-string (str "datastar=" (URLEncoder/encode js "UTF-8")))
      (and js (not get?)) (assoc :body (ByteArrayInputStream. (.getBytes js "UTF-8")))
      datastar?           (assoc-in [:headers "datastar-request"] "true")
      sid                 (assoc-in [:headers "cookie"] (str "sid=" sid))
      ua                  (assoc-in [:headers "user-agent"] ua))))

(defn- system [] (main/new-system (atom state/initial)))

(deftest pages-test
  (let [app (routes/app (system))
        r   (app (req :get "/"))]
    (is (= 200 (:status r)))
    (is (string/includes? (:body r) "data-init=\"@get(&apos;/customer/stream&apos;, {retry: &apos;always&apos;})\"") "hiccup escapes ' as &apos;; the browser decodes it")
    (is (some #(string/starts-with? % "sid=") (get-in r [:headers "Set-Cookie"])) "a new browser gets a sid cookie")
    (is (string/includes? (:body (app (req :get "/" :ua "x BrewShell/1"))) "class=\"shell\""))
    (is (= 200 (:status (app (req :get "/board")))))
    (is (= "application/json" (get-in (app (req :get "/native/path-config")) [:headers "Content-Type"])))))

(deftest datastar-request-guard-test
  (let [{:keys [!state] :as sys} (system)
        app (routes/app sys)
        line {:drink "latte" :size "medium" :milk "oat" :qty 1}]
    (is (= 403 (:status (app (req :post "/cart/items" :sid "a" :signals line)))))
    (is (empty? (:carts @!state)))
    (is (= 204 (:status (app (req :post "/cart/items" :sid "a" :signals line :datastar? true)))))
    (is (= [{:drink :latte :size :medium :milk :oat :qty 1}] (get-in @!state [:carts "a"])))))

(deftest command-errors-test
  (let [{:keys [!state] :as sys} (system)
        app (routes/app sys)]
    (swap! !state state/handle-command {:type :toggle-stock :milk :oat})
    (let [r (app (req :post "/cart/items" :sid "a" :datastar? true
                      :signals {:drink "latte" :size "small" :milk "oat" :qty 1}))]
      (is (= 200 (:status r)))
      (is (= "event: datastar-patch-signals\ndata: signals {\"_error\":\"Oat milk is sold out.\"}\n\n" (:body r))))))

(deftest order-flow-test
  (let [{:keys [!state] :as sys} (system)
        app (routes/app sys)]
    (app (req :post "/cart/items" :sid "a" :datastar? true :signals {:drink "mocha" :size "large" :milk "almond" :qty 2}))
    (is (= 204 (:status (app (req :post "/orders" :sid "a" :datastar? true :signals {:customer "Linus"})))))
    (is (= 204 (:status (app (req :post "/orders/1/advance" :datastar? true)))))
    (is (= :making (get-in @!state [:orders 1 :status])))
    (app (req :post "/stock/whole/toggle" :datastar? true))
    (is (= #{:whole} (:sold-out @!state)))
    (app (req :post "/native/device" :sid "a" :datastar? true :signals {:pushToken "tok-1"}))
    (is (= "tok-1" (get-in @!state [:devices "a"])))))

(deftest draft-test
  (let [{:keys [!state] :as sys} (system)
        app (routes/app sys)]
    (testing "picking a drink is a guarded command"
      (is (= 403 (:status (app (req :post "/draft" :sid "a" :signals {:drink "espresso"}))))))
    (testing "switching to espresso fixes size and empties milk; the server keeps the fitted draft"
      (let [r (app (req :post "/draft" :sid "a" :datastar? true :signals {:drink "espresso" :size "medium" :milk "oat"}))]
        (is (= 200 (:status r)))
        (is (= "event: datastar-patch-signals\ndata: signals {\"size\":\"single\",\"milk\":\"\"}\n\n" (:body r)))
        (is (= {:drink :espresso :size :single :milk nil} (get-in @!state [:drafts "a"])))))
    (testing "valid choices need no fixes: 204"
      (is (= 204 (:status (app (req :post "/draft" :sid "a" :datastar? true :signals {:drink "latte" :size "small" :milk "whole"}))))))
    (testing "a sold-out milk counts as invalid"
      (swap! !state state/handle-command {:type :toggle-stock :milk :whole})
      (is (= "event: datastar-patch-signals\ndata: signals {\"milk\":\"oat\"}\n\n"
             (:body (app (req :post "/draft" :sid "a" :datastar? true :signals {:drink "latte" :size "small" :milk "whole"}))))))
    (testing "the fitted draft renders selected in the customer view"
      (is (string/includes? (views/html-str (views/customer-app @!state "a")) "<option selected=\"\" value=\"oat\">")))))

(deftest fit-draft-test
  (is (= {:drink :chai :size :medium :milk :oat} (routes/fit-draft :chai {:size :medium :milk :oat} #{})))
  (is (= {:drink :chai :size :medium :milk :almond} (routes/fit-draft :chai {:size :small :milk :oat} #{:whole :oat})))
  (is (= {:drink :espresso :size :single :milk nil} (routes/fit-draft :espresso {:size :large :milk :oat} #{}))))
