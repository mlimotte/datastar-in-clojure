(ns brewboard.demo
  "A scripted Brew Board session over real HTTP, for CI and for reading.
  Opens a customer stream (Ada) and a board stream (Sam), runs commands, and
  prints what each stream receives."
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
  "Read an SSE stream on a background thread; print and keep each event."
  [port path label sid]
  (let [!events (atom [])
        resp    (.send client (request port "GET" path {:sid sid :datastar? true})
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
        (catch Exception e (println "stream reader failed:" (ex-message e) (class e)))))
    !events))

(defn- await-events [!events n]
  (loop [waited 0]
    (when (and (< (count @!events) n) (< waited 3000))
      (Thread/sleep 25)
      (recur (+ waited 25)))))

(defn- step [title port method path opts & streams]
  (println)
  (println (str "▶ " title))
  (println (str "  " method " " path (when-let [s (:signals opts)] (str "  signals " (json/generate-string s)))))
  (let [before (mapv (comp count deref) streams)
        resp   (send! port method path opts)]
    (println (str "  ← " (.statusCode resp)
                  (when-let [b (not-empty (.body resp))]
                    (str "\n" (string/join "\n" (map #(str "    " %) (string/split-lines (string/trim b))))))))
    (doseq [[s n] (map vector streams before)] (await-events s (inc n)))
    (Thread/sleep 100)))

(defn -main [& _]
  (let [port     (main/start! 0 (main/new-system))
        ada      "sid-ada"
        _        (println (str "Brew Board demo on port " port ". Ada is a customer; Sam runs the board."))
        _        (println "\n▶ Ada opens the customer page; Sam opens the board. Each stream starts with the whole view.")
        customer (open-stream! port "/customer/stream" "Ada" ada)
        board    (open-stream! port "/board/stream" "Sam" nil)]
    (await-events customer 1)
    (await-events board 1)
    (step (str "Ada picks Espresso in the drink drop-down (a command). The response fixes the size and milk"
               " signals; her stream re-renders with espresso's options.")
          port "POST" "/draft" {:sid ada :datastar? true :signals {:drink "espresso" :size "small" :milk "whole"}}
          customer)
    (step "Ada picks Latte again: latte options, and a valid size and milk."
          port "POST" "/draft" {:sid ada :datastar? true :signals {:drink "latte" :size "single" :milk ""}}
          customer)
    (step "Ada adds a medium oat latte. The command returns 204; her stream carries the new cart."
          port "POST" "/cart/items" {:sid ada :datastar? true :signals {:drink "latte" :size "medium" :milk "oat" :qty 1}}
          customer)
    (step "A forged request without the Datastar-Request header is refused."
          port "POST" "/orders" {:sid ada :signals {:customer "Mallory"}})
    (step "Ada places her order. Both streams re-render."
          port "POST" "/orders" {:sid ada :datastar? true :signals {:customer "Ada"}}
          customer board)
    (step "Sam starts making order #1."
          port "POST" "/orders/1/advance" {:datastar? true} customer board)
    (step "Sam marks it ready. Ada's stream also gets bridge scripts (haptic + notification; no-ops in a browser)."
          port "POST" "/orders/1/advance" {:datastar? true} customer board)
    (step "Sam marks oat milk sold out. Ada's drop-down shows it disabled, with no extra code."
          port "POST" "/stock/oat/toggle" {:datastar? true} customer board)
    (step "Ada tries to add another oat latte: the server refuses and patches the _error signal."
          port "POST" "/cart/items" {:sid ada :datastar? true :signals {:drink "latte" :size "small" :milk "oat" :qty 1}})
    (println "\nDone. Run it for real: clojure -M -m brewboard.main")
    (main/stop!)
    (shutdown-agents)
    (System/exit 0)))
