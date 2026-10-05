(ns brewboard.menu-test
  (:require [brewboard.menu :as menu]
            [clojure.test :refer [deftest is testing]]))

(deftest price-test
  (is (= 560 (menu/price-cents {:drink :latte :size :medium :milk :oat})))
  (is (= 400 (menu/price-cents {:drink :espresso :size :double :milk nil})))
  (is (= 1120 (menu/line-total {:drink :latte :size :medium :milk :oat :qty 2})))
  (is (= "$5.60" (menu/money 560))))

(deftest valid-line-test
  (let [ok {:drink :latte :size :medium :milk :oat :qty 1}]
    (is (nil? (menu/valid-line? ok #{})))
    (testing "sold-out milk, wrong size, missing milk, milk on a milk-less drink, bad qty"
      (is (= "Oat milk is sold out." (menu/valid-line? ok #{:oat})))
      (is (some? (menu/valid-line? (assoc ok :size :single) #{})))
      (is (some? (menu/valid-line? (assoc ok :milk nil) #{})))
      (is (some? (menu/valid-line? {:drink :espresso :size :single :milk :oat :qty 1} #{})))
      (is (nil? (menu/valid-line? {:drink :espresso :size :single :milk nil :qty 1} #{})))
      (is (some? (menu/valid-line? (assoc ok :qty 0) #{}))))))

(deftest first-valid-test
  (is (= :almond (menu/first-valid [:whole :oat :almond] #{:whole :oat})))
  (is (nil? (menu/first-valid [] #{}))))
