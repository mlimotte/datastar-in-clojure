(ns brewboard.views
  "Pure hiccup. Every render takes the whole state and returns the whole view;
  Datastar's morph applies only the difference in the browser."
  (:require [brewboard.menu :as menu]
            [cheshire.core :as json]
            [clojure.string :as string]
            [hiccup2.core :as h]
            [hiccup.util :refer [raw-string]]))

(def datastar-src
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.4/bundles/datastar.js")

(def bridge-js
  "Web → native. In the shell, forward to the native handler; in a browser, do nothing."
  "window.BrewShell = window.BrewShell || { post(msg) {
  if (window.webkit?.messageHandlers?.brew) window.webkit.messageHandlers.brew.postMessage(msg);
  else if (window.BrewAndroid) window.BrewAndroid.postMessage(JSON.stringify(msg));
  else console.debug('BrewShell: no native shell, ignoring', msg);
} };")

(defn html-str [hiccup] (str (h/html hiccup)))

(def status-label {:placed "Placed" :making "Making" :ready "Ready"})

;; ---------------------------------------------------------------- layout

(defn- header [shell? active]
  (when-not shell?
    [:header.top
     [:span.brand "☕ Brew Board"]
     [:nav
      [:a {:href "/" :class (when (= active :customer) "on")} "Order"]
      [:a {:href "/board" :class (when (= active :board) "on")} "Barista board"]]]))

(def tab-seed
  "A per-tab id. A cookie names the browser, and every tab shares it, so per-tab state
  (the draft, the error) is keyed by this instead. `ifmissing` keeps it across morphs;
  a full reload makes a new one. crypto.randomUUID needs https or localhost."
  "crypto.randomUUID()")

(defn page
  "A full HTML page. `signals` and the tab id are seeded on <body>; `root-attrs` (data-init
  that opens the stream, listeners) go on #root. Both sit outside #app, so the morph of
  #app never re-runs them. Datastar applies <body> before its children, so the signals
  exist before data-init opens the stream."
  [{:keys [title shell? active signals tab? root-attrs]} app]
  (str "<!DOCTYPE html>"
       (h/html
        [:html {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title title]
          [:link {:rel "stylesheet" :href "/brewboard.css"}]
          [:link {:rel "icon" :href "data:,"}]
          [:script (raw-string bridge-js)]
          [:script {:type "module" :src datastar-src}]]
         [:body (cond-> {:class (when shell? "shell")}
                  signals (assoc :data-signals (json/generate-string signals))
                  tab?    (assoc :data-signals:tabid__ifmissing tab-seed))
          [:div#root root-attrs
           (header shell? active)
           app]]])))

;; ---------------------------------------------------------------- the drop-down

(defn- option [value text & {:keys [disabled? selected?]}]
  [:option (cond-> {:value (name value)}
             disabled? (assoc :disabled "")
             selected? (assoc :selected ""))
   text])

(defn- plus [delta] (when (pos? delta) (str " +" (menu/money delta))))

(defn drink-options-view
  "The size and milk drop-downs for `drink`. Options are only ever HTML from the
  server; sold-out milks are shown disabled. Every change posts the draft, so the server's
  `choice` is the real one and renders `selected`. The morph syncs an option's selected
  property only when that attribute changes between renders: if the server did not know
  the choice, a re-render that shifts the option list would leave the wrong option selected."
  [drink sold-out {:keys [size milk]}]
  (let [{:keys [sizes milks name]} (menu/by-id drink)]
    [:div#drink-options.row
     [:label "Size"
      (into [:select#size {:data-bind:size "" :data-on:change "@post('/draft')"}]
            (map #(option % (str (menu/label %) (plus (menu/size-delta %))) :selected? (= % size))
                 sizes))]
     (if (seq milks)
       [:label "Milk"
        (into [:select#milk {:data-bind:milk "" :data-on:change "@post('/draft')"}]
              (map #(option % (str (menu/label %) (if (contains? sold-out %) " (sold out)" (plus (menu/milk-delta %))))
                            :disabled? (contains? sold-out %)
                            :selected? (= % milk))
                   milks))]
       [:label "Milk" [:p.muted (str name " has no milk")]])]))

(defn- draft-view [{:keys [drink] :as draft} sold-out]
  [:section#draft.card
   [:h2 "Order a drink"]
   [:div.row
    [:label "Drink"
     (into [:select#drink {:data-bind:drink "" :data-on:change "@post('/draft')"}]
           (map #(option (:id %) (str (:name %) " " (menu/money (:base %))) :selected? (= (:id %) drink))
                menu/drinks))]]
   (drink-options-view drink sold-out draft)
   [:div.row
    [:label "Qty" [:input#qty {:type "number" :min "1" :max "9" :data-bind:qty ""}]]
    ;; The server knows the draft (every change posts it), so it renders the price.
    ;; The browser never computes a price.
    [:div.price "Each " [:b (menu/money (menu/price-cents draft))]]]
   [:button.primary {:data-on:click "@post('/cart/items')"
                     :data-indicator:_adding ""
                     :data-attr:disabled "$_adding"}
    "Add to cart"]])

(defn- cart-view [lines]
  [:section#cart.card
   [:h2 "Your cart"]
   (if (empty? lines)
     [:p.muted "Nothing yet."]
     [:ul.lines
      (map-indexed
       (fn [i line]
         [:li [:span (menu/describe line)]
          [:span.amt (menu/money (menu/line-total line))]
          [:button.link {:data-on:click (str "@delete('/cart/items/" i "')") :title "Remove"} "✕"]])
       lines)])
   (when (seq lines)
     (list
      [:p.total "Total " [:b (menu/money (reduce + (map menu/line-total lines)))]]
      [:div.row
       [:label "Name" [:input#customer {:data-bind:customer "" :placeholder "for the cup"}]]
       [:button.primary {:data-on:click "@post('/orders')"} "Place order"]]))])

(defn- my-orders-view [orders]
  [:section#my-orders.card
   [:h2 "Your orders"]
   (if (empty? orders)
     [:p.muted "No orders yet."]
     [:ul.lines
      (for [{:keys [id status lines]} orders]
        [:li {:id (str "my-order-" id)}
         [:a {:href (str "/orders/" id)} (str "#" id)]
         [:span (string/join ", " (map menu/describe lines))]
         [:span {:class (str "pill " (name status))} (status-label status)]])])])

;; ---------------------------------------------------------------- customer

(defn customer-orders [state sid]
  (filter #(= sid (:sid %)) (vals (:orders state))))

(defn default-draft [state]
  {:drink :latte :size :small :milk (menu/first-valid menu/all-milks (:sold-out state))})

(defn draft
  "The draft the server last saw from tab `tabid`; a new tab starts on the first valid latte."
  [state tabid]
  (or (get-in state [:tabs tabid :draft]) (default-draft state)))

(defn customer-app
  "The whole customer view for one tab. The cart and orders belong to the browser (`sid`);
  the draft and the error belong to the tab (`tabid`)."
  [state sid tabid]
  [:main#app.customer
   [:p#error.error {:role "alert"} (get-in state [:tabs tabid :error])]
   (draft-view (draft state tabid) (:sold-out state))
   (cart-view (get-in state [:carts sid] []))
   (my-orders-view (customer-orders state sid))
   [:p.muted [:a {:href "/settings"} "Settings"]]])

(defn initial-signals
  "Form-draft signals for a fresh page. The tab id is seeded separately (`tab-seed`)."
  [state]
  (let [{:keys [drink size milk]} (default-draft state)]
    {:drink     (name drink)
     :size      (name size)
     :milk      (or (some-> milk name) "")
     :qty       1
     :customer  ""
     :pushToken ""}))

(def native-listener
  "Native → web: the shell dispatches a `brew:native` CustomEvent on window."
  "if (evt.detail.type === 'push-token') { $pushToken = evt.detail.token; @post('/native/device') }")

(defn customer-page
  "A fresh page: the tab id does not exist yet (the browser makes it), so #app renders the
  default draft. The stream's first event replaces it with this tab's view."
  [state sid shell?]
  (page {:title "Brew Board" :shell? shell? :active :customer
         :signals (initial-signals state) :tab? true
         :root-attrs {:data-init "@get('/customer/stream', {retry: 'always'})"
                      :data-on:brew:native__window native-listener}}
        (customer-app state sid nil)))

;; ---------------------------------------------------------------- order detail / settings (shell screens)

(defn order-app [state sid id]
  (let [{:keys [status lines customer] :as order} (get-in state [:orders id])]
    [:main#app.customer
     [:section.card
      (if (and order (= sid (:sid order)))
        (list [:h2 (str "Order #" id " for " customer)]
              [:p [:span {:class (str "pill big " (name status))} (status-label status)]]
              [:ul.lines (for [l lines] [:li [:span (menu/describe l)] [:span.amt (menu/money (menu/line-total l))]])])
        (list [:h2 (str "Order #" id)] [:p.muted "Picked up, or not yours."]))
      [:p [:a {:href "/"} "← Back to ordering"]]]]))

(defn order-page [state sid id shell?]
  (page {:title (str "Order #" id) :shell? shell?
         :root-attrs {:data-init (str "@get('/orders/" id "/stream', {retry: 'always'})")}}
        (order-app state sid id)))

(defn settings-page [state sid shell?]
  (page {:title "Settings" :shell? shell?}
        [:main#app.customer
         [:section.card
          [:h2 "Settings"]
          [:p "Push notifications: "
           [:b (if (get-in state [:devices sid]) "registered" "not registered")]]
          [:p.muted "In the native shell this screen opens as a modal; the rule lives in /native/path-config."]]]))

;; ---------------------------------------------------------------- board

(def advance-label {:placed "Start" :making "Ready" :ready "Picked up"})

(defn- order-card [{:keys [id customer lines status]}]
  [:li.order {:id (str "order-" id)}
   [:div.order-head [:b (str "#" id)] [:span customer]]
   [:ul (for [l lines] [:li (menu/describe l)])]
   [:button {:data-on:click (str "@post('/orders/" id "/advance')")} (advance-label status)]])

(defn board-app
  "The whole barista view: three status columns and the milk stock panel."
  [state]
  (let [by-status (group-by :status (vals (:orders state)))]
    [:main#app.board
     [:div.columns
      (for [status [:placed :making :ready]]
        [:section.card.column {:id (str "col-" (name status))}
         [:h2 (status-label status) " " [:span.count (count (by-status status))]]
         [:ul.orders (map order-card (by-status status))]])]
     [:section#stock.card
      [:h2 "Milk stock"]
      [:div.row
       (for [milk menu/all-milks
             :let [out? (contains? (:sold-out state) milk)]]
         [:button {:id (str "stock-" (name milk))
                   :class (if out? "stock out" "stock")
                   :data-on:click (str "@post('/stock/" (name milk) "/toggle')")}
          (menu/label milk) (if out? " — sold out" " — in stock")])]]]))

(defn board-page [state shell?]
  (page {:title "Barista board" :shell? shell? :active :board
         :root-attrs {:data-init "@get('/board/stream', {retry: 'always'})"}}
        (board-app state)))
