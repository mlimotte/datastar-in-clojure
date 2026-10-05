(ns brewboard.native-test
  (:require [brewboard.native :as native]
            [brewboard.state :as state]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]))

(deftest shell-request-test
  (is (true? (native/shell-request? {:headers {"user-agent" "Mozilla/5.0 (iPhone) BrewShell/1"}})))
  (is (not (native/shell-request? {:headers {"user-agent" "Mozilla/5.0"}})))
  (is (not (native/shell-request? {:headers {}}))))

(deftest path-config-test
  (let [rule-for (fn [path] (:presentation (first (filter #(some (fn [p] (re-find (re-pattern p) path)) (:patterns %))
                                                          (:rules native/path-config)))))]
    (is (= "root" (rule-for "/")))
    (is (= "push" (rule-for "/orders/12")))
    (is (= "modal" (rule-for "/settings")))
    (is (nil? (rule-for "/board")))))

(deftest bridge-script-test
  (is (string/includes? (native/bridge-script {:type "haptic" :style "success"})
                        "data: elements <script data-effect=\"el.remove()\">window.BrewShell?.post({\"type\":\"haptic\",\"style\":\"success\"})</script>")))

(def ^:private placed
  (-> state/initial
      (state/handle-command {:type :add-to-cart :sid "a" :line {:drink :chai :size :large :milk :oat :qty 1}})
      (state/handle-command {:type :place-order :sid "a" :customer "Grace"})
      (state/handle-command {:type :advance :id 1})))

(deftest ready-notifications-test
  (let [ready (state/handle-command placed {:type :advance :id 1})]
    (is (= [{:sid "a" :order-id 1 :message "Grace, your chai latte is ready"}]
           (native/ready-notifications placed ready)))
    (testing "only on the transition, not while it stays ready"
      (is (empty? (native/ready-notifications ready ready))))
    (testing "events go only to the ordering session"
      (is (= 2 (count (native/ready-events "a" placed ready))))
      (is (empty? (native/ready-events "b" placed ready))))))
