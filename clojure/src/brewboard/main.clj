(ns brewboard.main
  "Start Brew Board: `clojure -M -m brewboard.main` (PORT env var, default 8080)."
  (:require [brewboard.hub :as hub]
            [brewboard.routes :as routes]
            [brewboard.state :as state]
            [org.httpkit.server :as hk]))

(defonce !server (atom nil))

(defn new-system
  "A fresh state atom and hub, wired so every state change is broadcast."
  ([] (new-system (atom state/initial)))
  ([!state]
   (let [h (hub/new-hub)]
     (hub/watch-state! h !state)
     {:!state !state :hub h})))

(defn stop! []
  (when-let [s @!server]
    (hk/server-stop! s)
    (reset! !server nil)))

(defn start!
  "Start on `port` (0 lets the OS pick). Return the port."
  ([port] (start! port (new-system state/!state)))
  ([port sys]
   (stop!)
   (reset! !server (hk/run-server (routes/app sys) {:port port :legacy-return-value? false}))
   (hk/server-port @!server)))

(defn -main [& _]
  (let [port (start! (or (some-> (System/getenv "PORT") parse-long) 8080))]
    (println (str "customer: http://localhost:" port "/  board: http://localhost:" port "/board"))))
