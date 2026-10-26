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

(defn- open-tab! [!state sid tabid]
  (swap! !state state/handle-command {:type :open-tab :sid sid :tabid tabid :stream :s
                                      :draft (views/default-draft @!state)}))

(deftest command-errors-test
  (let [{:keys [!state] :as sys} (system)
        app (routes/app sys)
        bad {:tabid "t1" :drink "latte" :size "small" :milk "oat" :qty 1}]
    (open-tab! !state "a" "t1")
    (open-tab! !state "a" "t2")
    (swap! !state state/handle-command {:type :toggle-stock :milk :oat})
    (testing "a failed command answers 204 and stores the error on the tab that sent it"
      (is (= 204 (:status (app (req :post "/cart/items" :sid "a" :datastar? true :signals bad)))))
      (is (= "Oat milk is sold out." (get-in @!state [:tabs "t1" :error])))
      (is (nil? (get-in @!state [:tabs "t2" :error])) "another tab of the same browser is untouched"))
    (testing "the tab's view renders the error as HTML"
      (is (string/includes? (views/html-str (views/customer-app @!state "a" "t1"))
                            "<p class=\"error\" id=\"error\" role=\"alert\">Oat milk is sold out.</p>"))
      (is (string/includes? (views/html-str (views/customer-app @!state "a" "t2"))
                            "<p class=\"error\" id=\"error\" role=\"alert\"></p>")))
    (testing "the tab's next successful command clears it"
      (app (req :post "/cart/items" :sid "a" :datastar? true :signals (assoc bad :milk "whole")))
      (is (nil? (get-in @!state [:tabs "t1" :error]))))))

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
    (open-tab! !state "a" "t1")
    (open-tab! !state "a" "t2")
    (testing "switching to espresso fixes size and empties milk; the server keeps this tab's fitted draft"
      (let [r (app (req :post "/draft" :sid "a" :datastar? true
                        :signals {:tabid "t1" :drink "espresso" :size "medium" :milk "oat"}))]
        (is (= 200 (:status r)))
        (is (= "event: datastar-patch-signals\ndata: signals {\"size\":\"single\",\"milk\":\"\"}\n\n" (:body r)))
        (is (= {:drink :espresso :size :single :milk nil} (get-in @!state [:tabs "t1" :draft])))))
    (testing "the other tab of the same browser keeps its own draft"
      (is (= :latte (get-in @!state [:tabs "t2" :draft :drink])))
      (is (string/includes? (views/html-str (views/customer-app @!state "a" "t2")) "<option selected=\"\" value=\"latte\">"))
      (is (string/includes? (views/html-str (views/customer-app @!state "a" "t1")) "<option selected=\"\" value=\"espresso\">")))
    (testing "valid choices need no fixes: 204"
      (is (= 204 (:status (app (req :post "/draft" :sid "a" :datastar? true
                                    :signals {:tabid "t1" :drink "latte" :size "small" :milk "whole"}))))))
    (testing "a sold-out milk counts as invalid"
      (swap! !state state/handle-command {:type :toggle-stock :milk :whole})
      (is (= "event: datastar-patch-signals\ndata: signals {\"milk\":\"oat\"}\n\n"
             (:body (app (req :post "/draft" :sid "a" :datastar? true
                              :signals {:tabid "t1" :drink "latte" :size "small" :milk "whole"}))))))
    (testing "the fitted draft renders selected, and the server renders its price"
      (let [v (views/html-str (views/customer-app @!state "a" "t1"))]
        (is (string/includes? v "<option selected=\"\" value=\"oat\">"))
        (is (string/includes? v "Each <b>$5.10</b>") "small oat latte: 450 + 0 + 60")))
    (testing "a draft from a tab with no open stream is not stored"
      (app (req :post "/draft" :sid "a" :datastar? true :signals {:tabid "ghost" :drink "mocha" :size "small" :milk "whole"}))
      (is (nil? (get-in @!state [:tabs "ghost"]))))))

(deftest fit-draft-test
  (is (= {:drink :chai :size :medium :milk :oat} (routes/fit-draft :chai {:size :medium :milk :oat} #{})))
  (is (= {:drink :chai :size :medium :milk :almond} (routes/fit-draft :chai {:size :small :milk :oat} #{:whole :oat})))
  (is (= {:drink :espresso :size :single :milk nil} (routes/fit-draft :espresso {:size :large :milk :oat} #{}))))

(deftest adopt-draft-test
  (testing "a reconnecting tab brings its draft; it is fitted to the menu and the stock"
    (is (= {:draft {:drink :chai :size :medium :milk :almond} :fixes {}}
           (routes/adopt-draft state/initial {:drink "chai" :size "medium" :milk "almond"})))
    (is (= {:draft {:drink :chai :size :medium :milk :whole} :fixes {:milk "whole"}}
           (routes/adopt-draft (assoc state/initial :sold-out #{:oat}) {:drink "chai" :size "medium" :milk "oat"}))))
  (testing "no usable drink: the default draft, and fixes for every key"
    (is (= {:draft {:drink :latte :size :small :milk :whole}
            :fixes {:drink "latte" :size "small" :milk "whole"}}
           (routes/adopt-draft state/initial {})))))

(deftest bad-signals-test
  (let [app (routes/app (system))]
    (testing "a malformed datastar param or body is treated as no signals, not a 500"
      (is (= 200 (:status (app (assoc (req :get "/") :query-string "datastar=%7Bnot-json")))))
      (is (= 204 (:status (app (assoc (req :post "/orders" :sid "a" :datastar? true)
                                      :body (ByteArrayInputStream. (.getBytes "[1,2]" "UTF-8"))))))))
    (testing "a number where a string belongs, or a non-numeric index, is not a 500"
      (is (= 204 (:status (app (req :post "/draft" :sid "a" :datastar? true :signals {:tabid 5 :drink 1 :size true})))))
      (is (= 204 (:status (app (req :delete "/cart/items/abc" :sid "a" :datastar? true))))))))
