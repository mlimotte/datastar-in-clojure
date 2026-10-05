(ns lesson01.hypermedia-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [lesson01.hypermedia :as l]))

(deftest sse-event-test
  (testing "one data line"
    (is (= "event: datastar-patch-elements\ndata: elements <div id=\"a\">x</div>\n\n"
           (l/sse-event "datastar-patch-elements" ["elements <div id=\"a\">x</div>"]))))
  (testing "each data line gets its own prefix"
    (is (= "event: e\ndata: one\ndata: two\n\n"
           (l/sse-event "e" ["one" "two"]))))
  (testing "no data lines still ends with a blank line"
    (is (= "event: e\n\n" (l/sse-event "e" [])))))

(deftest hello-response-test
  (let [{:keys [status headers body]} (l/hello-response 3)]
    (is (= 200 status))
    (is (= "text/event-stream" (get headers "Content-Type")))
    (is (= "no-cache" (get headers "Cache-Control")))
    (is (string/starts-with? body "event: datastar-patch-elements\ndata: elements <div id=\"greeting\">"))
    (is (string/includes? body "Visit number 3."))
    (is (string/ends-with? body "</div>\n\n"))))

(deftest handler-test
  (testing "the page loads Datastar and wires the button"
    (let [body (string/replace (:body (l/handler {:request-method :get :uri "/"})) "&apos;" "'")]
      (is (string/includes? body l/datastar-cdn))
      (is (string/includes? body "data-on:click=\"@get('/hello')\""))))
  (testing "text/html variant carries the same element"
    (let [{:keys [headers body]} (l/handler {:request-method :get :uri "/hello-html"})]
      (is (= "text/html" (get headers "Content-Type")))
      (is (string/starts-with? body "<div id=\"greeting\">")))))
