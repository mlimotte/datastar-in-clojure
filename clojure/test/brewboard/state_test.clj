(ns brewboard.state-test
  (:require [brewboard.state :as state]
            [clojure.test :refer [deftest is testing]]))

(def latte {:drink :latte :size :medium :milk :oat :qty 1})

(defn- run [s & cmds] (reduce state/handle-command s cmds))

(deftest cart-and-order-test
  (let [s (run state/initial
               {:type :add-to-cart :sid "a" :line latte}
               {:type :add-to-cart :sid "a" :line (assoc latte :qty 2)}
               {:type :remove-from-cart :sid "a" :idx 0})]
    (is (= [(assoc latte :qty 2)] (get-in s [:carts "a"])))
    (let [s (run s {:type :place-order :sid "a" :customer " Ada "})]
      (is (= {:id 1 :sid "a" :customer "Ada" :status :placed :lines [(assoc latte :qty 2)]}
             (get-in s [:orders 1])))
      (is (nil? (get-in s [:carts "a"])) "placing an order empties the cart")
      (is (= 2 (:next-id s))))))

(deftest advance-test
  (let [s (run state/initial {:type :add-to-cart :sid "a" :line latte} {:type :place-order :sid "a" :customer "Ada"})]
    (is (= [:placed :making :ready]
           (map #(get-in % [:orders 1 :status])
                (take 3 (iterate #(state/handle-command % {:type :advance :id 1}) s)))))
    (testing "picked up orders leave the board"
      (is (empty? (:orders (run s {:type :advance :id 1} {:type :advance :id 1} {:type :advance :id 1})))))))

(deftest invalid-commands-test
  (testing "invalid commands return an error and do not change state"
    (let [s (run state/initial {:type :toggle-stock :milk :oat})]
      (is (= #{:oat} (:sold-out s)))
      (is (= "Oat milk is sold out." (state/validate s {:type :add-to-cart :sid "a" :line latte})))
      (is (= s (state/handle-command s {:type :add-to-cart :sid "a" :line latte})))
      (is (= "Your cart is empty." (state/validate s {:type :place-order :sid "a" :customer "Ada"})))
      (is (= "Tell us your name." (state/validate s {:type :place-order :sid "a" :customer "  "}))))))

(deftest command!-test
  (let [!s (atom state/initial)]
    (is (nil? (state/command! !s {:type :add-to-cart :sid "a" :line latte})))
    (is (= "No such order." (state/command! !s {:type :advance :id 99})))
    (is (= 1 (count (get-in @!s [:carts "a"]))))))

(deftest tabs-test
  (let [s (run state/initial
               {:type :open-tab :sid "a" :tabid "t1" :stream 1 :draft {:drink :latte}}
               {:type :choose-draft :tabid "t1" :draft {:drink :mocha}})]
    (is (= {:sid "a" :stream 1 :draft {:drink :mocha}} (get-in s [:tabs "t1"])))
    (testing "a close from an older stream of the same tab (a late close after a reconnect) is ignored"
      (let [s (run s {:type :open-tab :sid "a" :tabid "t1" :stream 2 :draft {:drink :chai}})]
        (is (some? (get-in (run s {:type :close-tab :tabid "t1" :stream 1}) [:tabs "t1"])))
        (is (nil? (get-in (run s {:type :close-tab :tabid "t1" :stream 2}) [:tabs "t1"])))))))

(deftest command!-errors-test
  (let [!s (atom (run state/initial {:type :open-tab :sid "a" :tabid "t1" :stream 1 :draft {}}))]
    (is (= "Your cart is empty." (state/command! !s {:type :place-order :sid "a" :tabid "t1" :customer "Ada"})))
    (is (= "Your cart is empty." (get-in @!s [:tabs "t1" :error])))
    (is (nil? (state/command! !s {:type :add-to-cart :sid "a" :tabid "t1" :line latte})))
    (is (nil? (get-in @!s [:tabs "t1" :error])) "success clears the tab's error")))
