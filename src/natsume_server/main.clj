(ns natsume-server.main
  (:gen-class)
  (:require [com.stuartsierra.component :as component]
            [environ.core :refer [env]]
            [natsume-server.system :as system]
            [clojure.java.io :as io]))

(def config
  {:http {:port (some-> env :port Integer.)}})

(defn -main [& args]
  (let [system (system/new-system config)]
    (println "Starting HTTP server on port" (-> system :http :port))
    (component/start system)))
