(ns natsume-server.main
  (:gen-class)
  (:require [com.stuartsierra.component :as component]
            [duct.middleware.errors :as errors]
            [environ.core :refer [env]]
            [natsume-server.system :as system]
            [clojure.java.io :as io]))

(def config
  {:http {:port (some-> env :port Integer.)}
   :app  {:middleware     [[errors/wrap-hide-errors :internal-error]]
          :internal-error (io/resource "errors/500.html")}})

(defn -main [& args]
  (let [system (system/new-system config)]
    (println "Starting HTTP server on port" (-> system :http :port))
    (component/start system)))
