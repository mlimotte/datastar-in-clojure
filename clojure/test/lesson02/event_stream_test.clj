(ns lesson02.event-stream-test
  "Unit tests, plus every golden case from Datastar's own SDK test suite (test/lesson02/cases)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [lesson02.event-stream :as es]))

(deftest send-event-test
  (testing "no options"
    (is (= "event: e\ndata: a\ndata: b\n\n" (es/send-event "e" ["a" "b"] {}))))
  (testing "id and retry come before data"
    (is (= "event: e\nid: 7\nretry: 2000\ndata: a\n\n"
           (es/send-event "e" ["a"] {:id 7 :retry 2000}))))
  (testing "a retry of 1000 is the default, so it is left out"
    (is (= "event: e\ndata: a\n\n" (es/send-event "e" ["a"] {:retry 1000})))))

(deftest patch-elements-test
  (testing "default mode outer is left out"
    (is (= "event: datastar-patch-elements\ndata: elements <p id=\"x\">1</p>\n\n"
           (es/patch-elements "<p id=\"x\">1</p>" {:mode :outer}))))
  (testing "options come before elements, one elements line per HTML line"
    (is (= (str "event: datastar-patch-elements\n"
                "data: selector #feed\n"
                "data: mode append\n"
                "data: elements <li>\n"
                "data: elements a</li>\n\n")
           (es/patch-elements "<li>\na</li>" {:selector "#feed" :mode "append"}))))
  (testing "namespace html is the default"
    (is (not (string/includes? (es/patch-elements "<p id=\"a\"></p>" {:namespace :html}) "namespace"))))
  (testing "svg namespace is sent"
    (is (string/includes? (es/patch-elements "<circle id=\"c\"/>" {:namespace :svg}) "data: namespace svg\n")))
  (testing "remove needs no elements"
    (is (= "event: datastar-patch-elements\ndata: selector #a\ndata: mode remove\n\n"
           (es/remove-elements "#a" {})))))

(deftest patch-signals-test
  (testing "a map becomes JSON"
    (is (= "event: datastar-patch-signals\ndata: signals {\"qty\":2,\"gone\":null}\n\n"
           (es/patch-signals (array-map :qty 2 :gone nil) {}))))
  (testing "only-if-missing comes first"
    (is (= "event: datastar-patch-signals\ndata: onlyIfMissing true\ndata: signals {\"a\":1}\n\n"
           (es/patch-signals {:a 1} {:only-if-missing true}))))
  (testing "a JSON string is sent as is, one line per line"
    (is (= "event: datastar-patch-signals\ndata: signals {\ndata: signals \"a\": 1}\n\n"
           (es/patch-signals "{\n\"a\": 1}" {})))))

(deftest execute-script-test
  (testing "auto-remove is the default"
    (is (= (str "event: datastar-patch-elements\n"
                "data: selector body\n"
                "data: mode append\n"
                "data: elements <script data-effect=\"el.remove()\">go()</script>\n\n")
           (es/execute-script "go()" {}))))
  (testing "attributes, no auto-remove"
    (is (string/includes? (es/execute-script "go()" {:auto-remove false :attributes {"type" "module"}})
                          "data: elements <script type=\"module\">go()</script>\n"))))

;; ---------------------------------------------------------------- golden cases

(defn- opts [ev]
  (cond-> {}
    (contains? ev "eventId")           (assoc :id (get ev "eventId"))
    (contains? ev "retryDuration")     (assoc :retry (get ev "retryDuration"))
    (contains? ev "selector")          (assoc :selector (get ev "selector"))
    (contains? ev "mode")              (assoc :mode (get ev "mode"))
    (contains? ev "useViewTransition") (assoc :use-view-transition (get ev "useViewTransition"))
    (contains? ev "namespace")         (assoc :namespace (get ev "namespace"))
    (contains? ev "onlyIfMissing")     (assoc :only-if-missing (get ev "onlyIfMissing"))
    (contains? ev "autoRemove")        (assoc :auto-remove (get ev "autoRemove"))
    (contains? ev "attributes")        (assoc :attributes (get ev "attributes"))))

(defn- run-event [ev]
  (case (get ev "type")
    "patchElements" (es/patch-elements (get ev "elements") (opts ev))
    "patchSignals"  (es/patch-signals (or (get ev "signals-raw") (get ev "signals")) (opts ev))
    "executeScript" (es/execute-script (get ev "script") (opts ev))))

(defn- normalize
  "Compare like Datastar's sdk/test/compare-sse.sh: data lines may come in any order across
  their first word (selector, mode, ...), but keep their order within the same word."
  [sse]
  (for [event (string/split sse #"\n\n")
        :when (not (string/blank? event))]
    (let [{data true other false} (group-by #(string/starts-with? % "data: ") (string/split-lines event))]
      [other (sort-by #(second (re-find #"^data: (\S+)" %)) data)])))

(deftest golden-cases-test
  (let [cases (->> (.listFiles (io/file "test/lesson02/cases"))
                   (filter #(.isDirectory %))
                   (sort-by #(.getName %)))]
    (is (= 19 (count cases)) "all get-cases from sdk/test are present")
    (doseq [dir cases]
      (testing (.getName dir)
        (let [events   (-> (io/file dir "input.json") slurp json/parse-string (get "events"))
              expected (slurp (io/file dir "output.txt"))]
          (is (= (normalize expected) (normalize (apply str (map run-event events))))))))))
