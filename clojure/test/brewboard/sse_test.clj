(ns brewboard.sse-test
  (:require [brewboard.sse :as sse]
            [clojure.test :refer [deftest is testing]]))

(deftest send-event-test
  (testing "id and a non-default retry come before data; retry 1000 is omitted"
    (is (= "event: x\nid: 7\nretry: 2000\ndata: a\ndata: b\n\n"
           (sse/send-event "x" ["a" "b"] {:id 7 :retry 2000})))
    (is (= "event: x\ndata: a\n\n" (sse/send-event "x" ["a"] {:retry 1000})))))

(deftest patch-elements-test
  (is (= "event: datastar-patch-elements\ndata: elements <div id=\"feed\"><span>1</span></div>\n\n"
         (sse/patch-elements "<div id=\"feed\"><span>1</span></div>")))
  (testing "golden order: selector, mode, useViewTransition; outer mode is omitted"
    (is (= (str "event: datastar-patch-elements\nid: event1\nretry: 2000\n"
                "data: selector div\ndata: mode append\ndata: useViewTransition true\n"
                "data: elements <div>Merge</div>\n\n")
           (sse/patch-elements "<div>Merge</div>" {:id "event1" :retry 2000 :selector "div"
                                                   :mode :append :use-view-transition true})))
    (is (not (re-find #"mode" (sse/patch-elements "<p id=\"a\"></p>" {:mode :outer})))))
  (testing "each line of HTML gets its own elements line"
    (is (= "event: datastar-patch-elements\ndata: elements <ul id=\"l\">\ndata: elements </ul>\n\n"
           (sse/patch-elements "<ul id=\"l\">\n</ul>")))))

(deftest remove-and-script-test
  (is (= "event: datastar-patch-elements\ndata: selector #feed\ndata: mode remove\n\n"
         (sse/remove-elements "#feed")))
  (is (= (str "event: datastar-patch-elements\ndata: selector body\ndata: mode append\n"
              "data: elements <script data-effect=\"el.remove()\">console.log('Here')</script>\n\n")
         (sse/execute-script "console.log('Here')"))))

(deftest patch-signals-test
  (is (= "event: datastar-patch-signals\ndata: signals {\"_error\":\"no\"}\n\n"
         (sse/patch-signals {:_error "no"})))
  (is (= "event: datastar-patch-signals\ndata: onlyIfMissing true\ndata: signals {\"a\":null}\n\n"
         (sse/patch-signals "{\"a\":null}" {:only-if-missing true}))))

(deftest read-signals-test
  (is (= {:drink "latte"} (sse/read-signals {:request-method :get :query-params {"datastar" "{\"drink\":\"latte\"}"}})))
  (is (= {:customer "Ada"} (sse/read-signals {:request-method :post :body (java.io.ByteArrayInputStream. (.getBytes "{\"customer\":\"Ada\"}"))})))
  (is (= {} (sse/read-signals {:request-method :get :query-params {}})))
  (testing "malformed or non-object input gives {}, never an exception"
    (is (= {} (sse/read-signals {:request-method :get :query-params {"datastar" "{oops"}})))
    (is (= {} (sse/read-signals {:request-method :delete :query-params {"datastar" "42"}})))
    (is (= {} (sse/read-signals {:request-method :post :body "[\"a\"]"})))))
