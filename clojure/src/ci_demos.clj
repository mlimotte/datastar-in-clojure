(ns ci-demos
  "Run every lesson demo and the capstone demo in one JVM. CI uses this. Each demo! leaves the
  agent thread pool alive, so the next demo can still use future; shut it down once at the end."
  (:require [lesson01.hypermedia]
            [lesson02.event-stream]
            [lesson03.signals]
            [lesson04.where-state-lives]
            [lesson05.routes]
            [lesson06.live]
            [lesson07.native-shell]
            [brewboard.demo]))

(defn -main [& _]
  (doseq [demo! [lesson01.hypermedia/demo!
                 lesson02.event-stream/demo!
                 lesson03.signals/demo!
                 lesson04.where-state-lives/demo!
                 lesson05.routes/demo!
                 lesson06.live/demo!
                 lesson07.native-shell/demo!
                 brewboard.demo/demo!]]
    (demo!))
  (shutdown-agents))
