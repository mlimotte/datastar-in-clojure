(ns lesson07.brew
  "Brew Board's domain, copied from part 6 so this lesson stands alone: the menu, prices, and
  the pure command function. Part 7 adds one command, :register-device."
  (:require [clojure.string :as string]))

;; ---------------------------------------------------------------------------
;; Menu and prices (copied from part 3)

(def menu
  [{:id :espresso   :name "Espresso"   :base 300 :sizes [:single :double]        :milks []}
   {:id :americano  :name "Americano"  :base 350 :sizes [:small :large]          :milks []}
   {:id :latte      :name "Latte"      :base 450 :sizes [:small :medium :large]  :milks [:whole :oat :almond]}
   {:id :cappuccino :name "Cappuccino" :base 425 :sizes [:small :medium]         :milks [:whole :oat]}
   {:id :mocha      :name "Mocha"      :base 500 :sizes [:small :medium :large]  :milks [:whole :oat :almond]}
   {:id :chai       :name "Chai Latte" :base 450 :sizes [:medium :large]         :milks [:whole :oat :almond]}])

(def drinks (into {} (map (juxt :id identity)) menu))
(def size-delta {:single 0 :double 100 :small 0 :medium 50 :large 100})
(def milk-delta {:whole 0 :oat 60 :almond 60})

(defn price-cents
  "Price of one drink: base plus size and milk deltas."
  [{:keys [drink size milk]}]
  (+ (get-in drinks [drink :base]) (size-delta size) (milk-delta milk 0)))

(defn line-total [{:keys [qty] :as line}] (* qty (price-cents line)))

(defn dollars [cents] (format "$%d.%02d" (quot cents 100) (rem cents 100)))

(defn label [k] (some-> k name string/capitalize))

;; ---------------------------------------------------------------------------
;; State and commands (copied from part 6)

(def initial-state
  "Per-customer state (carts, orders, devices) is keyed by the sid cookie; per-tab state (draft,
  error) by the tab id: {tabid {:sid … :stream … :draft … :error …}}."
  {:sold-out #{} :carts {} :orders (sorted-map) :next-id 1 :tabs {} :devices {}})

(def next-status {:placed :making :making :ready :ready :picked-up})

(defn line-error
  "Why a draft line can't go in a cart, or nil when it can."
  [{:keys [sold-out]} {:keys [drink size milk qty]}]
  (let [{:keys [sizes milks] :as d} (drinks drink)]
    (cond
      (nil? d)                                     "Pick a drink."
      (not (some #{size} sizes))                   "Pick a size this drink comes in."
      (and (seq milks) (not (some #{milk} milks))) "Pick a milk."
      (and (empty? milks) milk)                    "This drink takes no milk."
      (sold-out milk)                              (str (label milk) " milk is sold out.")
      (not (pos-int? qty))                         "Quantity must be at least 1.")))

(defn fit-draft
  "The draft with size and milk replaced by the drink's first valid choice when they don't fit it.
  A sold-out milk doesn't fit."
  [{:keys [sold-out]} {:keys [drink size milk]}]
  (let [{:keys [id sizes milks]} (or (drinks drink) (drinks :latte))
        valid-milks (remove sold-out milks)]
    {:drink id
     :size  (if (some #{size} sizes) size (first sizes))
     :milk  (if (some #{milk} valid-milks) milk (first valid-milks))}))

(defn- set-error
  "Record (or clear, with nil) the message a tab shows. Only for a tab the server knows."
  [state tabid error]
  (cond-> state
    (get-in state [:tabs tabid]) (update-in [:tabs tabid] #(if error (assoc % :error error) (dissoc % :error)))))

(defn handle-command
  "Apply one command to the state. Pure: returns the new state.
  A command from a tab first clears that tab's error; a command that fails records a new one."
  [state {:keys [type sid tabid stream line idx customer id milk draft token]}]
  (let [state (set-error state tabid nil)]
    (case type
      :add-to-cart      (if-let [err (line-error state line)]
                          (set-error state tabid err)
                          (update-in state [:carts sid] (fnil conj []) line))
      :remove-from-cart (update-in state [:carts sid]
                                   #(into [] (keep-indexed (fn [i l] (when (not= i idx) l))) %))
      :place-order      (let [lines (get-in state [:carts sid])]
                          (cond
                            (empty? lines)           (set-error state tabid "Your cart is empty.")
                            (string/blank? customer) (set-error state tabid "Enter your name.")
                            :else
                            (-> state
                                (assoc-in [:orders (:next-id state)]
                                          {:id (:next-id state) :sid sid :customer (string/trim customer)
                                           :status :placed :lines lines})
                                (update :next-id inc)
                                (update :carts dissoc sid))))
      :advance          (if-let [status (get-in state [:orders id :status])]
                          (if (= :picked-up (next-status status))
                            (update state :orders dissoc id)
                            (assoc-in state [:orders id :status] (next-status status)))
                          state)
      :toggle-stock     (update state :sold-out #(if (% milk) (disj % milk) (conj % milk)))
      :choose-draft     (update-in state [:tabs tabid] assoc :sid sid :draft (fit-draft state draft))
      :open-tab         (assoc-in state [:tabs tabid] {:sid sid :stream stream :draft (fit-draft state draft)})
      :close-tab        (cond-> state
                          (= stream (get-in state [:tabs tabid :stream])) (update :tabs dissoc tabid))
    ;; New in part 7: the push token the native shell sent for this browser session.
      :register-device  (assoc-in state [:devices sid] token)
      state)))
