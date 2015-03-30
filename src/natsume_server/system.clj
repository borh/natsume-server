(ns natsume-server.system
  (:require [com.stuartsierra.component :as component]
            [meta-merge.core :refer [meta-merge]]
            [natsume-server.component.database :refer [database]]
            [natsume-server.component.http-server :refer [http-server]]
            [natsume-server.component.service :refer [service]]
            [natsume-server.endpoint.api :refer [api-endpoint]]))

(def base-config
  {:http {:port 3000}})

(defn new-system [config]
  (let [config (meta-merge base-config config)]
    (if (:server config)
      (-> (component/system-map
            :http (http-server (:http config))
            :database (database config)
            :api (service api-endpoint :api))
          (component/system-using
            {:http [:api #_:webapp]
             :api  [:database]}))
      ;; Offline mode for data loading.
      (component/system-map :database (database config)))))
