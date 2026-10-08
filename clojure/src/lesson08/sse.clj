(ns lesson08.sse
  "The SSE writer from part 2, cut down to what this part uses, copied so the lesson stands alone.
  Pure functions return strings or Ring response maps. Nothing here writes to a socket."
  (:require [cheshire.core :as json]
            [clojure.string :as string]))

(defn send-event
  "Format one SSE event: event line, optional id and retry, data lines, blank line."
  [event-type data-lines {:keys [id retry]}]
  (str "event: " event-type "\n"
       (when id (str "id: " id "\n"))
       (when (and retry (not= retry 1000)) (str "retry: " retry "\n"))
       (apply str (map #(str "data: " % "\n") data-lines))
       "\n"))

(defn patch-elements
  "A datastar-patch-elements event. Omit options that hold their defaults."
  ([html] (patch-elements html {}))
  ([html {:keys [selector mode] :as opts}]
   (send-event "datastar-patch-elements"
               (concat (when selector [(str "selector " selector)])
                       (when (and mode (not= mode "outer")) [(str "mode " mode)])
                       (map #(str "elements " %) (string/split-lines html)))
               opts)))

(def sse-headers
  {"Content-Type"  "text/event-stream"
   "Cache-Control" "no-cache"})

(defn sse-response
  "A one-shot Ring response. The client reads the events until EOF."
  [& events]
  {:status 200 :headers sse-headers :body (apply str events)})

(defn html-response
  "A plain text/html response. Datastar morphs each top-level element by id.
  `headers` may carry patch options, e.g. {\"datastar-mode\" \"append\"}."
  ([html] (html-response html {}))
  ([html headers]
   {:status  200
    :headers (merge {"Content-Type" "text/html; charset=utf-8"} headers)
    :body    html}))

(defn read-signals
  "Signals from a Datastar request, keyword keys.
  GET and DELETE carry them in the `datastar` query param; other methods in the JSON body.
  Unparseable or non-object JSON gives {}, so a crafted request can't cause a 500."
  [{:keys [request-method query-params body]}]
  (let [raw    (if (#{:get :delete} request-method)
                 (get query-params "datastar")
                 (cond-> body (not (string? body)) (some-> slurp)))
        parsed (when-not (string/blank? raw)
                 (try (json/parse-string raw true) (catch Exception _ nil)))]
    (if (map? parsed) parsed {})))
