(ns brewboard.hub
  "Open streams and broadcast. A subscriber is a map:
    :send-fn  (fn [event-string]) writes to its connection
    :view-fn  (fn [state] hiccup) renders its whole #app
    :extra-fn (fn [old-state new-state] [event-string ...]) optional, e.g. bridge scripts
  The hub remembers the last HTML sent to each subscriber and skips identical renders."
  (:require [brewboard.sse :as sse]
            [brewboard.views :as views]))

(defn new-hub [] (atom {}))

(defn- render [{:keys [view-fn]} state]
  (views/html-str (view-fn state)))

(defn subscribe!
  "Register a subscriber and send it the whole view of the current state right away
  (a reconnecting browser gets the current truth, not a diff). It holds the hub's lock, so it
  cannot record an older view than a broadcast that runs at the same time."
  [hub id {:keys [send-fn] :as sub} !state]
  (locking hub
    (let [html (render sub @!state)]
      (swap! hub assoc id (assoc sub :last-html html))
      (send-fn (sse/patch-elements html)))))

(defn unsubscribe! [hub id]
  (swap! hub dissoc id))

(defn broadcast!
  "Send each subscriber its view of the current state (when it changed) and its extra events for
  the change from `old-state` to `new-state`. Broadcasts run one at a time and read `!state`
  inside the lock, so a broadcast that runs late cannot send an older view. Extra events describe
  one change, so they use the states the watch passed in."
  [hub !state old-state new-state]
  (locking hub
    (let [state @!state]
      (doseq [[id {:keys [send-fn extra-fn last-html] :as sub}] @hub
              :let [html   (render sub state)
                    events (cond-> []
                             (not= html last-html) (conj (sse/patch-elements html))
                             extra-fn              (into (extra-fn old-state new-state)))]
              :when (seq events)]
        (swap! hub #(cond-> % (contains? % id) (assoc-in [id :last-html] html)))
        (send-fn (apply str events))))))

(defn watch-state!
  "Broadcast on every change of `!state`."
  [hub !state]
  (add-watch !state ::broadcast
             (fn [_ _ old new]
               (when-not (identical? old new)
                 (broadcast! hub !state old new)))))
