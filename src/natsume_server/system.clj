(ns natsume-server.system
  (:require [com.stuartsierra.component :as component]
            ;;[duct.component.endpoint :refer [endpoint-component]]
            [duct.component.handler :refer [handler-component]]
            [duct.middleware.not-found :refer [wrap-not-found]]
            [ring.middleware.webjars :refer [wrap-webjars]]
            [meta-merge.core :refer [meta-merge]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [natsume-server.component.database :refer [database]]
            [natsume-server.component.server :refer [server]]
            [natsume-server.endpoint.api :refer [api-endpoint]]
            [natsume-server.endpoint.webapp :refer [webapp-endpoint]]
            [natsume-server.endpoint :refer [endpoint-component]]
            [clojure.java.io :as io]))

(def base-config
  {:http {:port 3000}
   :app  {:middleware [[wrap-not-found :not-found]
                       [wrap-webjars]
                       [wrap-defaults :defaults]]
          :not-found  (io/resource "errors/404.html")
          :defaults   site-defaults}})

(defn new-system [config]
  (let [config (meta-merge base-config config)]
    (-> (component/system-map
         :app      (handler-component (:app config))
         :http     (server (:http config) (:database config))
         :database (database config)
         :api      (endpoint-component (-> config :database :connection) api-endpoint)
         :webapp   (endpoint-component (-> config :database :connection) webapp-endpoint))
        (component/system-using
         {:http [:app :database]
          :api  [:database]
          :app  [:api :webapp]}))))
