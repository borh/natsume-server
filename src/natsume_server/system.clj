(ns natsume-server.system
  (:require [com.stuartsierra.component :as component]
            [meta-merge.core :refer [meta-merge]]
            [natsume-server.component.database :refer [database]]
            [natsume-server.endpoint.api :refer [new-api-app]]))

(def base-config
  {:http {:port 3000}})

(defn new-system [config]
  (let [config (meta-merge base-config config)]
    (if (:server config)
      (-> (component/system-map
           :database (database config)
           :api (new-api-app (:http config)))
          (component/system-using
           {:api [:database]}))
      ;; Offline mode for data loading.
      (component/system-map :database (database config)))))
