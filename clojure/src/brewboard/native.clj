(ns brewboard.native
  "The native shell's server side: detection, navigation rules, and the bridge."
  (:require [brewboard.menu :as menu]
            [brewboard.sse :as sse]
            [cheshire.core :as json]
            [clojure.string :as string]))

(def user-agent-token "BrewShell/1")

(defn shell-request?
  "True when the request comes from the native shell (it appends BrewShell/1 to its User-Agent)."
  [req]
  (some-> (get-in req [:headers "user-agent"]) (string/includes? user-agent-token) boolean))

(def path-config
  "Navigation rules the shell fetches at launch. Changing them is a server deploy, not an app release."
  {:rules [{:patterns ["^/$"]          :presentation "root"}
           {:patterns ["^/orders/\\d+$"] :presentation "push"}
           {:patterns ["^/settings$"]  :presentation "modal"}]})

(defn bridge-script
  "An SSE event that runs window.BrewShell.post(msg) in the page. In the shell this
  reaches native code; in a plain browser it is a no-op. Every < in the JSON is written as
  \u003c, so user text cannot end or confuse the <script> tag."
  [msg]
  (sse/execute-script (str "window.BrewShell?.post(" (string/replace (json/generate-string msg) "<" "\\u003c") ")")))

(defn- first-drink [{:keys [lines]}]
  (some-> lines first :drink menu/drink-name string/lower-case))

(defn ready-notifications
  "Orders that became :ready between `old` and `new`, as [{:sid :message}]."
  [old new]
  (for [[id {:keys [status sid customer] :as order}] (:orders new)
        :when (and (= :ready status) (not= :ready (get-in old [:orders id :status])))]
    {:sid sid :order-id id :message (str customer ", your " (first-drink order) " is ready")}))

(defn ready-events
  "The bridge events for one session: a success haptic and a local notification."
  [sid old new]
  (for [{:keys [message] :as n} (ready-notifications old new)
        :when (= sid (:sid n))
        msg [{:type "haptic" :style "success"}
             {:type "notify" :title "Brew Board" :body message}]]
    (bridge-script msg)))
