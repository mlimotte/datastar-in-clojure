(ns brewboard.state
  "The whole app state in one atom, and pure command handlers over it."
  (:require [brewboard.menu :as menu]
            [clojure.string :as string]))

(def initial
  {:sold-out #{}
   :carts    {}
   :drafts   {}     ;; sid → {:drink :size :milk} last seen by the server, so re-renders keep the drop-downs consistent
   :orders   (sorted-map)
   :devices  {}     ;; sid → push token, from the native shell
   :next-id  1})

(defonce !state (atom initial))

(def next-status {:placed :making :making :ready :ready :picked-up})

(defn validate
  "Return a message when the command cannot run against `state`, else nil."
  [state {:keys [type sid line customer id milk]}]
  (case type
    :add-to-cart  (menu/valid-line? line (:sold-out state))
    :place-order  (cond
                    (string/blank? customer)              "Tell us your name."
                    (empty? (get-in state [:carts sid]))  "Your cart is empty.")
    :advance      (when-not (get-in state [:orders id]) "No such order.")
    :toggle-stock (when-not (some #{milk} menu/all-milks) "No such milk.")
    nil))

(defn- place-order [{:keys [next-id] :as state} sid customer]
  (-> state
      (assoc-in [:orders next-id] {:id next-id :sid sid :customer (string/trim customer)
                                   :status :placed :lines (get-in state [:carts sid])})
      (update :carts dissoc sid)
      (update :next-id inc)))

(defn- advance [state id]
  (let [status (next-status (get-in state [:orders id :status]))]
    (if (= :picked-up status)
      (update state :orders dissoc id)
      (assoc-in state [:orders id :status] status))))

(defn- toggle [s x] (if (contains? s x) (disj s x) (conj s x)))

(defn- remove-index [v idx]
  (into (subvec v 0 idx) (subvec v (inc idx))))

(defn handle-command
  "Apply a command to the state. Invalid commands leave the state unchanged."
  [state {:keys [type sid line idx customer id milk draft token] :as cmd}]
  (if (validate state cmd)
    state
    (case type
      :add-to-cart      (update-in state [:carts sid] (fnil conj []) line)
      :remove-from-cart (cond-> state
                          (< -1 idx (count (get-in state [:carts sid])))
                          (update-in [:carts sid] remove-index idx))
      :place-order      (place-order state sid customer)
      :advance          (advance state id)
      :toggle-stock     (update state :sold-out toggle milk)
      :choose-draft     (assoc-in state [:drafts sid] draft)
      :register-device  (assoc-in state [:devices sid] token)
      state)))

(defn command!
  "Validate then apply `cmd` to the atom. Return the error message, or nil on success."
  [!state cmd]
  (or (validate @!state cmd)
      (do (swap! !state handle-command cmd) nil)))
