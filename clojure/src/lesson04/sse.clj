(ns lesson04.sse
  "The SSE writer from part 2 and read-signals from part 3, copied so this lesson stands alone."
  (:require [cheshire.core :as json]
            [clojure.string :as string]))

(defn send-event
  "Format one SSE event. Add an id line if id is set. Add a retry line unless retry is nil or 1000."
  [event-type data-lines {:keys [id retry]}]
  (str "event: " event-type "\n"
       (when id (str "id: " id "\n"))
       (when (and retry (not= 1000 retry)) (str "retry: " retry "\n"))
       (string/join (map #(str "data: " % "\n") data-lines))
       "\n"))

(defn- prefix-lines
  "Split text on newlines and put the prefix in front of each line."
  [prefix text]
  (map #(str prefix " " %) (string/split-lines text)))

(defn patch-elements
  "A datastar-patch-elements event. Send only options that differ from the defaults."
  [html {:keys [selector mode use-view-transition view-transition-selector namespace] :as opts}]
  (send-event "datastar-patch-elements"
              (concat (when selector [(str "selector " selector)])
                      (when (and mode (not= "outer" (name mode))) [(str "mode " (name mode))])
                      (when use-view-transition ["useViewTransition true"])
                      (when (and use-view-transition view-transition-selector)
                        [(str "viewTransitionSelector " view-transition-selector)])
                      (when (and namespace (not= "html" (name namespace)))
                        [(str "namespace " (name namespace))])
                      (when html (prefix-lines "elements" html)))
              opts))

(defn remove-elements
  "Remove every element that matches the CSS selector."
  [selector opts]
  (patch-elements nil (assoc opts :selector selector :mode :remove)))

(defn patch-signals
  "A datastar-patch-signals event. Signals is a map (encoded as JSON) or a JSON string.
  The client merges it as a JSON Merge Patch: a null value removes that signal."
  [signals {:keys [only-if-missing] :as opts}]
  (send-event "datastar-patch-signals"
              (concat (when only-if-missing ["onlyIfMissing true"])
                      (prefix-lines "signals" (cond-> signals (not (string? signals)) json/generate-string)))
              opts))

(defn execute-script
  "Run JavaScript in the browser: append a <script> to body. With auto-remove (the default)
  the script tag removes itself after it runs."
  [js {:keys [auto-remove attributes] :or {auto-remove true} :as opts}]
  (let [attrs (cond-> (mapv (fn [[k v]] (str " " (name k) "=\"" v "\"")) attributes)
                auto-remove (conj " data-effect=\"el.remove()\""))]
    (patch-elements (str "<script" (string/join attrs) ">" js "</script>")
                    (assoc opts :selector "body" :mode :append))))

(defn sse-response
  "A one-shot Ring response. The client reads the events until the body ends."
  [& events]
  {:status  200
   :headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"}
   :body    (apply str events)})

(defn html-response
  "A text/html response. Datastar morphs each top-level element into the element with the same id."
  [html]
  {:status 200 :headers {"Content-Type" "text/html"} :body html})

(defn read-signals
  "Read the signals Datastar sent: the datastar query parameter for GET and DELETE, the JSON
  body for other methods. Return a map with keyword keys."
  [{:keys [request-method query-params body]}]
  (let [raw (if (#{:get :delete} request-method)
              (get query-params "datastar")
              (some-> body slurp))]
    (if (string/blank? raw) {} (json/parse-string raw true))))
