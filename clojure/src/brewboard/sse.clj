(ns brewboard.sse
  "Datastar's SSE wire format, written by hand from sdk/ADR.md (no SDK).
  Pure functions return event strings. `sse-response` wraps them as a one-shot Ring
  response. `read-signals` parses what the browser sends."
  (:require [cheshire.core :as json]
            [clojure.string :as string]))

(def stream-headers
  {"Content-Type"  "text/event-stream"
   "Cache-Control" "no-cache"})

(defn send-event
  "Format one SSE event. `retry` is omitted when it is the default 1000."
  [event-type data-lines {:keys [id retry]}]
  (str "event: " event-type "\n"
       (when id (str "id: " id "\n"))
       (when (and retry (not= 1000 retry)) (str "retry: " retry "\n"))
       (apply str (map #(str "data: " % "\n") data-lines))
       "\n"))

(defn- prefixed-lines [prefix text]
  (map #(str prefix " " %) (string/split-lines text)))

(defn patch-elements
  "A datastar-patch-elements event. Data lines follow the golden SDK test order."
  ([html] (patch-elements html {}))
  ([html {:keys [selector mode use-view-transition namespace] :as opts}]
   (send-event "datastar-patch-elements"
               (cond-> []
                 selector                         (conj (str "selector " selector))
                 (and mode (not= "outer" (name mode))) (conj (str "mode " (name mode)))
                 use-view-transition              (conj "useViewTransition true")
                 (and namespace (not= "html" (name namespace))) (conj (str "namespace " (name namespace)))
                 (seq html)                       (into (prefixed-lines "elements" html)))
               opts)))

(defn remove-elements
  ([selector] (remove-elements selector {}))
  ([selector opts] (patch-elements nil (assoc opts :selector selector :mode :remove))))

(defn patch-signals
  "A datastar-patch-signals event. `signals` is a map (encoded with cheshire) or a JSON string.
  A nil value removes that signal (RFC 7386 merge patch)."
  ([signals] (patch-signals signals {}))
  ([signals {:keys [only-if-missing] :as opts}]
   (send-event "datastar-patch-signals"
               (cond-> []
                 only-if-missing (conj "onlyIfMissing true")
                 true            (into (prefixed-lines "signals"
                                                       (if (string? signals)
                                                         signals
                                                         (json/generate-string signals)))))
               opts)))

(defn execute-script
  "Run JavaScript in the browser: a patch-elements event that appends a <script> to body."
  ([js] (execute-script js {}))
  ([js {:keys [auto-remove] :or {auto-remove true} :as opts}]
   (patch-elements (str "<script" (when auto-remove " data-effect=\"el.remove()\"") ">" js "</script>")
                   (merge opts {:selector "body" :mode :append}))))

(defn sse-response
  "A one-shot SSE Ring response: the browser reads the events, then EOF."
  [& events]
  {:status 200 :headers stream-headers :body (apply str events)})

(def no-content {:status 204 :headers {} :body nil})

(defn read-signals
  "Signals as a keyword map. GET/DELETE carry them in query param `datastar`;
  other methods in the JSON body. Input that is not a JSON object gives {}, so a
  crafted request cannot cause a 500."
  [{:keys [request-method query-params body]}]
  (let [raw (if (#{:get :delete} request-method)
              (get query-params "datastar")
              (cond-> body (not (string? body)) (some-> slurp)))
        v   (when-not (string/blank? raw)
              (try (json/parse-string raw true) (catch Exception _ nil)))]
    (if (map? v) v {})))
