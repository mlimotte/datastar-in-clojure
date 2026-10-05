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

(defn page
  "A full HTML page. `root-attrs` go on #root, outside #app, so the morph of #app
  never re-runs them (data-signals, data-init that opens the stream)."
  [{:keys [title shell? active root-attrs]} app]
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
         [:body {:class (when shell? "shell")}
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

(defn price-expression
  "A data-computed expression for the draft price, generated from the menu so the
  client's estimate and the server's price-cents use the same numbers."
  []
  (let [js-map (fn [m] (json/generate-string (update-keys m name)))]
    (str "(" (js-map (update-vals menu/by-id :base)) "[$drink] || 0)"
         " + (" (js-map menu/size-delta) "[$size] || 0)"
         " + (" (js-map menu/milk-delta) "[$milk] || 0)")))

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
    [:div.price {:data-computed:price (price-expression)}
     "Each " [:b {:data-ignore-morph "" :data-text "'$' + ($price / 100).toFixed(2)"}]]]
   [:button.primary {:data-on:click "$_error = ''; @post('/cart/items')"
                     :data-indicator:_adding ""
                     :data-attr:disabled "$_adding"}
    "Add to cart"]
   ;; data-show and data-text write to this element; data-ignore-morph stops a re-render
   ;; from wiping what they wrote (morph keeps old nodes that carry it in both trees).
   [:p.error {:data-ignore-morph "" :data-show "$_error != ''" :data-text "$_error"}]])

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
       [:button.primary {:data-on:click "$_error = ''; @post('/orders')"} "Place order"]]))])

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

(defn draft
  "The draft the server last saw for `sid`; a new session starts on the first valid latte."
  [state sid]
  (or (get-in state [:drafts sid])
      {:drink :latte :size :small :milk (menu/first-valid menu/all-milks (:sold-out state))}))

(defn customer-app
  "The whole customer view for one session."
  [state sid]
  [:main#app.customer
   (draft-view (draft state sid) (:sold-out state))
   (cart-view (get-in state [:carts sid] []))
   (my-orders-view (customer-orders state sid))
   [:p.muted [:a {:href "/settings"} "Settings"]]])

(defn initial-signals [state sid]
  (let [{:keys [drink size milk]} (draft state sid)]
    {:drink     (name drink)
     :size      (name size)
     :milk      (or (some-> milk name) "")
     :qty       1
     :customer  ""
     :pushToken ""
     :_error    ""}))

(def native-listener
  "Native → web: the shell dispatches a `brew:native` CustomEvent on window."
  "if (evt.detail.type === 'push-token') { $pushToken = evt.detail.token; @post('/native/device') }")

(defn customer-page [state sid shell?]
  (page {:title "Brew Board" :shell? shell? :active :customer
         :root-attrs {:data-signals (json/generate-string (initial-signals state sid))
                      :data-init "@get('/customer/stream', {retry: 'always'})"
                      :data-on:brew:native__window native-listener}}
        (customer-app state sid)))

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
