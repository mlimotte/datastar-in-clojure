(ns brewboard.hub-test
  (:require [brewboard.hub :as hub]
            [brewboard.state :as state]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]))

(defn- sink [] (let [!sent (atom [])] [!sent #(swap! !sent conj %)]))

(deftest subscribe-sends-whole-view-test
  (let [h (hub/new-hub) [!sent send-fn] (sink)]
    (hub/subscribe! h :x {:send-fn send-fn :view-fn (fn [s] [:main#app (count (:sold-out s))])} state/initial)
    (is (= ["event: datastar-patch-elements\ndata: elements <main id=\"app\">0</main>\n\n"] @!sent))))

(deftest broadcast-test
  (let [h      (hub/new-hub)
        [!a a] (sink)
        [!b b] (sink)
        !s     (atom state/initial)]
    (hub/watch-state! h !s)
    (hub/subscribe! h :a {:send-fn a :view-fn (fn [s] [:main#app (count (:sold-out s))])} @!s)
    (hub/subscribe! h :b {:send-fn b :view-fn (fn [s] [:main#app (count (:carts s))])} @!s)
    (swap! !s state/handle-command {:type :toggle-stock :milk :oat})
    (testing "only subscribers whose view changed get an event"
      (is (= 2 (count @!a)))
      (is (string/includes? (last @!a) ">1</main>"))
      (is (= 1 (count @!b))))
    (testing "unsubscribed viewers get nothing"
      (hub/unsubscribe! h :a)
      (swap! !s state/handle-command {:type :toggle-stock :milk :oat})
      (is (= 2 (count @!a))))))

(deftest extra-events-test
  (let [h (hub/new-hub) [!sent send-fn] (sink)]
    (hub/subscribe! h :x {:send-fn send-fn :view-fn (constantly [:main#app])
                          :extra-fn (fn [old new] (when (not= old new) ["EXTRA"]))} state/initial)
    (hub/broadcast! h state/initial (assoc state/initial :next-id 9))
    (is (= "EXTRA" (last @!sent)) "extra events go out even when the view did not change")))
