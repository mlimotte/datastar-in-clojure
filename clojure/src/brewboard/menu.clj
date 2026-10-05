(ns brewboard.menu
  "The six drinks, their sizes and milks, and prices in cents.")

(def drinks
  [{:id :espresso   :name "Espresso"   :base 300 :sizes [:single :double]        :milks []}
   {:id :americano  :name "Americano"  :base 350 :sizes [:small :large]          :milks []}
   {:id :latte      :name "Latte"      :base 450 :sizes [:small :medium :large]  :milks [:whole :oat :almond]}
   {:id :cappuccino :name "Cappuccino" :base 425 :sizes [:small :medium]         :milks [:whole :oat]}
   {:id :mocha      :name "Mocha"      :base 500 :sizes [:small :medium :large]  :milks [:whole :oat :almond]}
   {:id :chai       :name "Chai Latte" :base 450 :sizes [:medium :large]         :milks [:whole :oat :almond]}])

(def by-id (into {} (map (juxt :id identity)) drinks))

(def size-delta {:single 0 :double 100 :small 0 :medium 50 :large 100})
(def milk-delta {:whole 0 :oat 60 :almond 60})
(def all-milks [:whole :oat :almond])

(defn label [k]
  (when k
    (get {:single "Single" :double "Double" :small "Small" :medium "Medium" :large "Large"
          :whole "Whole" :oat "Oat" :almond "Almond"}
         k (name k))))

(defn drink-name [drink] (get-in by-id [drink :name]))

(defn price-cents
  "Price of one drink: base + size delta + milk delta."
  [{:keys [drink size milk]}]
  (+ (get-in by-id [drink :base]) (size-delta size 0) (milk-delta milk 0)))

(defn line-total [{:keys [qty] :as line}]
  (* qty (price-cents line)))

(defn money [cents]
  (format "$%d.%02d" (quot cents 100) (rem cents 100)))

(defn first-valid
  "First choice in `choices` that is not in `unavailable`, or nil."
  [choices unavailable]
  (first (remove (set unavailable) choices)))

(defn valid-line?
  "Is this line orderable with the current stock? Returns nil when valid, else a message."
  [{:keys [drink size milk qty]} sold-out]
  (let [{:keys [sizes milks] :as d} (by-id drink)]
    (cond
      (nil? d)                               "Pick a drink."
      (not (some #{size} sizes))             (str (:name d) " doesn't come in that size.")
      (and (seq milks) (not (some #{milk} milks))) (str "Pick a milk for the " (:name d) ".")
      (and (empty? milks) milk)              (str (:name d) " has no milk.")
      (contains? sold-out milk)              (str (label milk) " milk is sold out.")
      (not (and (int? qty) (<= 1 qty 9)))    "Quantity must be 1 to 9.")))

(defn describe [{:keys [drink size milk qty]}]
  (str qty " × " (label size) " " (drink-name drink) (when milk (str " (" (label milk) ")"))))
