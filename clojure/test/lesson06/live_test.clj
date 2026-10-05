(ns lesson06.live-test
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [lesson06.live :as l]))

(def latte {:drink :latte :size :medium :milk :oat :qty 1})

(def two-customers
  (-> l/initial-state
      (assoc-in [:carts "ada"] [latte])
      (l/handle-command {:type :place-order :sid "ada" :customer "Ada"})
      (assoc-in [:carts "grace"] [(assoc latte :drink :mocha)])
      (l/handle-command {:type :place-order :sid "grace" :customer "Grace"})))

(defn- render [state viewer] (l/html (l/view-for state viewer)))

(deftest view-for-test
  (testing "every view is one <main id=\"app\">"
    (is (string/starts-with? (render l/initial-state {:kind :customer :sid "ada"}) "<main id=\"app\">"))
    (is (string/starts-with? (render l/initial-state {:kind :board}) "<main id=\"app\">")))
  (testing "a customer sees only their own orders"
    (let [v (render two-customers {:kind :customer :sid "ada"})]
      (is (string/includes? v "#1 for Ada: placed"))
      (is (not (string/includes? v "Grace")))
      (is (string/includes? v "id=\"drink-options\""))))
  (testing "the board sees every order and the milk stock"
    (let [v (render (update two-customers :sold-out conj :oat) {:kind :board})]
      (is (string/includes? v "#1 Ada (placed)"))
      (is (string/includes? v "#2 Grace (placed)"))
      (is (string/includes? v "Oat (sold out)")))))

(defn- recorder [] (let [sent (atom [])] [sent #(swap! sent conj %)]))

(deftest broadcast-test
  (let [hub (l/make-hub)
        [ada-sent ada-fn] (recorder)
        [sam-sent sam-fn] (recorder)]
    (l/subscribe! hub :ada ada-fn {:kind :customer :sid "ada"})
    (l/subscribe! hub :sam sam-fn {:kind :board})
    (testing "a new subscriber gets the whole view as one patch-elements event"
      (is (= #{:ada :sam} (set (l/broadcast! hub l/initial-state))))
      (is (= 1 (count @ada-sent)))
      (is (string/starts-with? (first @ada-sent) "event: datastar-patch-elements\ndata: elements <main id=\"app\">")))
    (testing "an unchanged view is not sent again"
      (is (= [] (l/broadcast! hub l/initial-state)))
      (is (= 1 (count @ada-sent) (count @sam-sent))))
    (testing "only viewers whose view changed are sent to"
      (is (= [:ada] (l/broadcast! hub (assoc-in l/initial-state [:carts "ada"] [latte]))))
      (is (= 2 (count @ada-sent)))
      (is (= 1 (count @sam-sent))))
    (testing ":last remembers what was sent"
      (is (= (render (assoc-in l/initial-state [:carts "ada"] [latte]) {:kind :customer :sid "ada"})
             (get-in @hub [:ada :last]))))))

(deftest stream-lifecycle-test
  (let [hub (l/make-hub)
        !state (atom l/initial-state)
        [sent send-fn] (recorder)
        {:keys [on-open on-close]} (l/stream-lifecycle hub !state {:kind :board})]
    (testing "on-open subscribes and sends the current view at once"
      (on-open send-fn)
      (is (= 1 (count @hub)))
      (is (= 1 (count @sent)))
      (is (string/includes? (first @sent) "Barista board")))
    (testing "two streams for the same viewer are two subscribers"
      ((:on-open (l/stream-lifecycle hub !state {:kind :board})) (fn [_]))
      (is (= 2 (count @hub))))
    (testing "on-close unsubscribes"
      (on-close)
      (is (= 1 (count @hub))))
    (testing "a reconnect gets the whole view again"
      (let [[again send-again] (recorder)]
        ((:on-open (l/stream-lifecycle hub !state {:kind :board})) send-again)
        (is (= 1 (count @again)))))))

(defn- call [handler method uri & {:keys [signals sid]}]
  (handler (cond-> {:request-method method :uri uri :headers {"datastar-request" "true"}}
             sid (assoc-in [:headers "cookie"] (str "sid=" sid))
             (and signals (= :get method))
             (assoc :query-string (str "datastar=" (java.net.URLEncoder/encode (json/generate-string signals) "UTF-8")))
             (and signals (not= :get method))
             (assoc :body (java.io.ByteArrayInputStream. (.getBytes (json/generate-string signals) "UTF-8"))))))

(deftest live-routes-test
  (let [!state (atom l/initial-state)
        hub (l/make-hub)
        app (l/app !state hub)
        [sent send-fn] (recorder)]
    (l/watch! !state hub)
    ((:on-open (l/stream-lifecycle hub !state {:kind :customer :sid "ada"})) send-fn)
    (testing "the page opens its stream with data-init and keeps signals outside #app"
      (let [body (:body (call app :get "/" :sid "ada"))]
        (is (string/includes? body "data-init=\"@get(&apos;/customer/stream&apos;, {retry: &apos;always&apos;})\""))
        (is (re-find #"<body data-signals=" body))
        (is (string/includes? body "<main id=\"app\">"))))
    (testing "commands answer 204 and the stream carries the change"
      (is (= 204 (:status (call app :post "/stock/oat/toggle"))))
      (is (= 2 (count @sent)))
      (is (string/includes? (last @sent) "Oat (sold out)")))
    (testing "add-to-cart answers only $_error"
      (let [{:keys [body]} (call app :post "/cart/items" :sid "ada" :signals {:drink "latte" :size "medium" :milk "whole" :qty 1})]
        (is (= "event: datastar-patch-signals\ndata: signals {\"_error\":\"\"}\n\n" body))
        (is (string/includes? (last @sent) "1 × Medium Whole Latte"))))
    (testing "choosing a drink is a command: the draft moves to the server"
      (let [{:keys [status body]} (call app :post "/draft" :sid "ada" :signals {:drink "espresso" :size "medium" :milk "whole"})]
        (is (= 200 status))
        (is (string/includes? body "data: signals {\"size\":\"single\",\"milk\":\"\"}"))
        (is (= {:drink :espresso :size :single :milk nil} (get-in @!state [:drafts "ada"])))
        (is (string/includes? (last @sent) "<option selected=\"\" value=\"single\">"))
        (is (string/includes? (last @sent) "<option value=\"double\">"))))
    (testing "the client-written error survives a re-render untouched"
      (is (string/includes? (last @sent) "<p class=\"error\" data-ignore-morph=\"\" data-text=\"$_error\"></p>")))
    (testing "a draft that still fits answers 204"
      (is (= 204 (:status (call app :post "/draft" :sid "ada" :signals {:drink "latte" :size "large" :milk "almond"})))))
    (testing "the draft command is guarded like the others"
      (is (= 403 (:status (app {:request-method :post :uri "/draft" :headers {}})))))
    (testing "there is no GET /drink-options in part 6"
      (is (= 404 (:status (call app :get "/drink-options" :sid "ada")))))))

(deftest drink-options-view-test
  (let [v (l/html (l/drink-options-view :chai #{:oat} {:size :medium :milk :oat}))]
    (testing "the stored choice renders selected, so the morph can't leave a stale option showing"
      (is (string/includes? v "<option selected=\"\" value=\"medium\">"))
      (is (string/includes? v "<option value=\"large\">")))
    (testing "a sold-out milk is disabled (and still selected if it was the choice)"
      (is (string/includes? v "<option disabled=\"\" selected=\"\" value=\"oat\">Oat (sold out)</option>")))
    (testing "the size and milk selects post the draft on change"
      (is (= 2 (count (re-seq #"data-on:change=\"@post\(&apos;/draft&apos;\)\"" v)))))))

(deftest fit-draft-test
  (testing "a fitting draft is unchanged"
    (is (= {:drink :latte :size :large :milk :oat} (l/fit-draft l/initial-state {:drink :latte :size :large :milk :oat}))))
  (testing "size and milk fall back to the first valid choice"
    (is (= {:drink :cappuccino :size :small :milk :whole}
           (l/fit-draft l/initial-state {:drink :cappuccino :size :large :milk :almond}))))
  (testing "a sold-out milk doesn't fit"
    (is (= :almond (:milk (l/fit-draft {:sold-out #{:whole :oat}} {:drink :latte :size :small :milk :oat}))))))
