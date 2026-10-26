(ns brewboard.hub-test
  (:require [brewboard.hub :as hub]
            [brewboard.state :as state]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]))

(defn- sink [] (let [!sent (atom [])] [!sent #(swap! !sent conj %)]))

(deftest subscribe-sends-whole-view-test
  (let [h (hub/new-hub) [!sent send-fn] (sink)]
    (hub/subscribe! h :x {:send-fn send-fn :view-fn (fn [s] [:main#app (count (:sold-out s))])} (atom state/initial))
    (is (= ["event: datastar-patch-elements\ndata: elements <main id=\"app\">0</main>\n\n"] @!sent))))

(deftest broadcast-test
  (let [h      (hub/new-hub)
        [!a a] (sink)
        [!b b] (sink)
        !s     (atom state/initial)]
    (hub/watch-state! h !s)
    (hub/subscribe! h :a {:send-fn a :view-fn (fn [s] [:main#app (count (:sold-out s))])} !s)
    (hub/subscribe! h :b {:send-fn b :view-fn (fn [s] [:main#app (count (:carts s))])} !s)
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
                          :extra-fn (fn [old new] (when (not= old new) ["EXTRA"]))} (atom state/initial))
    (hub/broadcast! h (atom state/initial) state/initial (assoc state/initial :next-id 9))
    (is (= "EXTRA" (last @!sent)) "extra events go out even when the view did not change")))

(deftest late-broadcast-test
  (let [h      (hub/new-hub)
        [!a a] (sink)
        !s     (atom state/initial)
        view   (fn [s] [:main#app (count (:sold-out s))])]
    (hub/subscribe! h :a {:send-fn a :view-fn view} !s)
    (let [s1 (state/handle-command @!s {:type :toggle-stock :milk :oat})
          s2 (state/handle-command s1 {:type :toggle-stock :milk :almond})]
      (reset! !s s2)
      (testing "a broadcast renders the current state, not the state its watch received"
        (hub/broadcast! h !s s1 s2)
        (is (string/includes? (last @!a) ">2</main>")))
      (testing "a broadcast that runs late sends nothing older"
        (hub/broadcast! h !s state/initial s1)
        (is (= 2 (count @!a)))))))

(deftest close-during-broadcast-test
  (testing "a stream that closes mid-broadcast stays unsubscribed, and later broadcasts still work"
    (let [h      (hub/new-hub)
          [!b b] (sink)
          !s     (atom state/initial)
          view   (fn [s] [:main#app (count (:sold-out s))])]
      (hub/subscribe! h :b {:send-fn b :view-fn view} !s)
      (swap! h assoc :a {:send-fn identity :view-fn (fn [s] (hub/unsubscribe! h :a) (view s))})
      (hub/broadcast! h !s @!s @!s)
      (is (= #{:b} (set (keys @h))))
      (swap! !s state/handle-command {:type :toggle-stock :milk :oat})
      (hub/broadcast! h !s state/initial @!s)
      (is (= 2 (count @!b))))))
