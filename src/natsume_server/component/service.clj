(ns natsume-server.component.service
  (:require [com.stuartsierra.component :as component]
            [io.pedestal.http :as http]))

(defrecord Service [routes service-name]
  component/Lifecycle

  (start [component]
    (println ";; Starting service" service-name)

    (assoc component
      :service {::http/routes routes}
      :connection (-> component :database :connection)))

  (stop [component]
    (println ";; Stopping service" service-name)

    (dissoc component :service :connection)))

(defn service [routes service-name]
  (->Service routes service-name))
