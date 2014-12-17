(ns natsume-server.endpoint
  (:require [com.stuartsierra.component :as component]))

(defrecord EndpointComponent [conn build-routes]
  component/Lifecycle
  (start [component]
    (if (:routes component)
      component
      (assoc component :routes (build-routes component) :connection conn)))
  (stop [component]
    (dissoc component :routes :connection)))

(defn endpoint-component [conn build-routes]
  (->EndpointComponent conn build-routes))
