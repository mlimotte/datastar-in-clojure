(ns brewboard.views-test
  (:require [brewboard.state :as state]
            [brewboard.views :as views]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]))

(defn- html [hiccup] (views/html-str hiccup))

(deftest drink-options-view-test
  (testing "options come from the menu, in menu order"
    (let [s (html (views/drink-options-view :cappuccino #{} {}))]
      (is (string/includes? s "id=\"drink-options\""))
      (is (= ["small" "medium" "whole" "oat"] (map second (re-seq #"<option value=\"(\w+)\"" s))))))
  (testing "the chosen size and milk render selected"
    (let [s (html (views/drink-options-view :latte #{} {:size :large :milk :almond}))]
      (is (string/includes? s "<option selected=\"\" value=\"large\">"))
      (is (string/includes? s "<option selected=\"\" value=\"almond\">"))))
  (testing "sold-out milks are disabled and labelled"
    (is (string/includes? (html (views/drink-options-view :latte #{:oat} {}))
                          "<option disabled=\"\" value=\"oat\">Oat (sold out)</option>")))
  (testing "a drink with no milks renders no milk select"
    (let [s (html (views/drink-options-view :espresso #{} {}))]
      (is (not (string/includes? s "id=\"milk\"")))
      (is (string/includes? s "Espresso has no milk")))))

(deftest no-boolean-attrs-test
  (testing "valueless data-* attributes render as empty strings, never name=\"name\""
    (let [s (views/customer-page state/initial "a" false)]
      (is (string/includes? s "data-bind:drink=\"\""))
      (is (not (re-find #"data-bind:\w+=\"data-bind" s))))))

(deftest customer-view-test
  (let [s (-> state/initial
              (state/handle-command {:type :add-to-cart :sid "a" :line {:drink :latte :size :medium :milk :oat :qty 1}})
              (state/handle-command {:type :add-to-cart :sid "b" :line {:drink :mocha :size :small :milk :whole :qty 1}}))
        a (html (views/customer-app s "a"))]
    (is (string/includes? a "1 × Medium Latte (Oat)"))
    (is (not (string/includes? a "Mocha (Whole)")) "a customer sees only their own cart")
    (is (string/includes? a "$5.60"))))

(deftest initial-signals-test
  (is (= {:drink "latte" :size "small" :milk "whole" :qty 1 :customer "" :pushToken "" :_error ""}
         (views/initial-signals state/initial "a")))
  (is (= "almond" (:milk (views/initial-signals (assoc state/initial :sold-out #{:whole :oat}) "a")))))

(deftest shell-layout-test
  (is (string/includes? (views/board-page state/initial false) "Barista board"))
  (is (not (string/includes? (views/customer-page state/initial "a" true) "<header")) "the shell draws its own chrome"))

(deftest board-view-test
  (let [s (-> state/initial
              (state/handle-command {:type :add-to-cart :sid "a" :line {:drink :latte :size :medium :milk :oat :qty 1}})
              (state/handle-command {:type :place-order :sid "a" :customer "Ada"})
              (state/handle-command {:type :toggle-stock :milk :oat}))
        b (html (views/board-app s))]
    (is (re-find #"id=\"col-placed\".*#1.*Ada.*Start" b))
    (is (string/includes? b "Oat — sold out"))))
