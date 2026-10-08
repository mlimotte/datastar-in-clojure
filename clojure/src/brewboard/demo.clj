(ns brewboard.demo
  "A scripted Brew Board session over real HTTP, for CI and for reading.
  Ada has two tabs open (one browser, so one sid cookie, but two tab ids); Sam has the
  board. Runs commands and prints what each stream receives."
  (:require [brewboard.main :as main]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Version HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)))

(def ^HttpClient client
  (-> (HttpClient/newBuilder) (.version HttpClient$Version/HTTP_1_1) .build))

(defn- request
  [port method path {:keys [signals datastar? sid]}]
  (let [json-body (some-> signals json/generate-string)
        get?      (#{"GET" "DELETE"} method)
        uri       (str "http://localhost:" port path
                       (when (and get? json-body)
                         (str "?datastar=" (URLEncoder/encode json-body StandardCharsets/UTF_8))))]
    (cond-> (HttpRequest/newBuilder (URI/create uri))
      datastar? (.header "Datastar-Request" "true")
      sid       (.header "Cookie" (str "sid=" sid))
      true      (.method method (if (and json-body (not get?))
                                  (HttpRequest$BodyPublishers/ofString json-body)
                                  (HttpRequest$BodyPublishers/noBody)))
      true      .build)))

(defn- send! [port method path opts]
  (.send client (request port method path opts) (HttpResponse$BodyHandlers/ofString)))

(defn- text-of
  "Visible text of an HTML string, whitespace collapsed."
  [html]
  (-> html (string/replace #"<option[^>]*>[^<]*</option>" "") (string/replace #"<[^>]+>" " ")
      (string/replace #"\s+" " ") string/trim))

(defn- summarize [event-lines]
  (let [[event & data] event-lines
        data    (map #(string/replace % #"^data: " "") data)
        html    (string/join "\n" (keep #(second (re-matches #"elements (.*)" %)) data))
        options (map second (re-seq #"<option[^>]*>([^<]*)</option>" html))]
    (cond
      (string/includes? html "<script")
      (str event "  script: " (second (re-find #"<script[^>]*>(.*)</script>" html)))

      (string/includes? html "id=\"app\"")
      (str event "  whole #app (" (count html) " chars): " (text-of html)
           (when-let [oat (first (filter #(string/starts-with? % "Oat") options))]
             (str "  [milk option: " oat "]")))

      (seq html) (str event "  " html)
      :else      (str event "  " (string/join " | " data)))))

(defn- open-stream!
  "Read an SSE stream on a background thread; print and keep each event. `signals` go in
  the `datastar` query param, as the browser sends them when data-init opens the stream."
  [port path label sid signals]
  (let [!events (atom [])
        resp    (.send client (request port "GET" path {:sid sid :datastar? true :signals signals})
                       (HttpResponse$BodyHandlers/ofInputStream))]
    (future
      (try
        (reduce (fn [acc line]
                  (if (string/blank? line)
                    (do (when (seq acc)
                          (let [s (summarize acc)]
                            (println (str "  [" label "] " s))
                            (swap! !events conj s)))
                        [])
                    (conj acc line)))
                []
                (line-seq (io/reader (.body resp))))
        (catch java.io.IOException _ nil) ;; the demo closed the stream
        (catch Exception e (println "stream reader failed:" (ex-message e) (class e)))))
    !events))

(defn- await-events [!events n]
  (loop [waited 0]
    (when (and (< (count @!events) n) (< waited 3000))
      (Thread/sleep 25)
      (recur (+ waited 25)))))

(defn- step
  "Send one request. Wait for one event on each of `streams`; report how many events each
  of `quiet` streams got (it should be none)."
  [title port method path opts & {:keys [streams quiet]}]
  (println)
  (println (str "▶ " title))
  (println (str "  " method " " path (when-let [s (:signals opts)] (str "  signals " (json/generate-string s)))))
  (let [before (mapv (comp count deref) streams)
        quiet0 (into {} (for [[label s] quiet] [label (count @s)]))
        resp   (send! port method path opts)]
    (println (str "  ← " (.statusCode resp)
                  (when-let [b (not-empty (.body resp))]
                    (str "\n" (string/join "\n" (map #(str "    " %) (string/split-lines (string/trim b))))))))
    (doseq [[s n] (map vector streams before)] (await-events s (inc n)))
    (Thread/sleep 150)
    (doseq [[label s] quiet]
      (println (str "  [" label "] " (- (count @s) (quiet0 label)) " events: its view did not change")))))

(def ^:private latte-draft {:drink "latte" :size "small" :milk "whole" :qty 1})

(defn demo!
  "Run the narrated demo: start a server, make real requests, print what happens, stop."
  []
  (let [port (main/start! 0 (main/new-system))
        ada  "sid-ada"
        tab1 (assoc latte-draft :tabid "tab-1")
        tab2 (assoc latte-draft :tabid "tab-2")
        _    (println (str "Brew Board demo on port " port ". Ada is a customer with two tabs open; Sam runs the board."))
        _    (println "\n▶ Each stream opens with the tab's signals (its tabid and draft) and starts with the whole view.")
        t1   (open-stream! port "/customer/stream" "Ada tab 1" ada tab1)
        _    (await-events t1 1)
        t2   (open-stream! port "/customer/stream" "Ada tab 2" ada tab2)
        _    (await-events t2 1)
        sam  (open-stream! port "/board/stream" "Sam" nil nil)]
    (await-events sam 1)
    (step (str "In tab 1, Ada picks Espresso (a command). The response fixes the size and milk signals;"
               " tab 1's stream re-renders with espresso's options and price.")
          port "POST" "/draft" {:sid ada :datastar? true :signals (assoc tab1 :drink "espresso")}
          :streams [t1] :quiet [["Ada tab 2" t2]])
    (step "In tab 1, Ada picks Latte again: latte options, and a valid size and milk."
          port "POST" "/draft" {:sid ada :datastar? true :signals (assoc tab1 :size "single" :milk "")}
          :streams [t1])
    (step "In tab 2, Ada adds a medium oat latte. The cart belongs to the browser, so both tabs re-render."
          port "POST" "/cart/items" {:sid ada :datastar? true :signals (assoc tab2 :size "medium" :milk "oat")}
          :streams [t1 t2])
    (step "A forged request without the Datastar-Request header is refused."
          port "POST" "/orders" {:sid ada :signals {:customer "Mallory"}})
    (step "Ada places her order from tab 1. Every stream re-renders."
          port "POST" "/orders" {:sid ada :datastar? true :signals (assoc tab1 :customer "Ada")}
          :streams [t1 t2 sam])
    (step "Sam starts making order #1."
          port "POST" "/orders/1/advance" {:datastar? true} :streams [t1 sam])
    (step "Sam marks it ready. Ada's streams also get bridge scripts (haptic + notification; no-ops in a browser)."
          port "POST" "/orders/1/advance" {:datastar? true} :streams [t1 sam])
    (step "Sam marks oat milk sold out. Ada's drop-downs show it disabled, with no extra code."
          port "POST" "/stock/oat/toggle" {:datastar? true} :streams [t1 sam])
    (step (str "In tab 1, Ada tries to add an oat latte. The command answers 204; the error is tab 1's"
               " server state, so only tab 1's view shows it.")
          port "POST" "/cart/items" {:sid ada :datastar? true :signals (assoc tab1 :milk "oat")}
          :streams [t1] :quiet [["Ada tab 2" t2]])
    (step "Tab 1's next command (picking a mocha) clears the error."
          port "POST" "/draft" {:sid ada :datastar? true :signals (assoc tab1 :drink "mocha")}
          :streams [t1])
    (println "\nDone. Run it for real: clojure -M -m brewboard.main")
    (main/stop!)))

(defn -main [& _]
  (demo!)
  (shutdown-agents)
  (System/exit 0))
