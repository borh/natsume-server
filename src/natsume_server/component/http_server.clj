(ns natsume-server.component.http-server
  (:require [com.stuartsierra.component :as component]
            [immutant.web :as web]
            [io.pedestal.http :as http]))

(defn- find-routes [component]
  ;;(clojure.pprint/pprint component)

  (first (keep #(:routes (second %)) component)))

(defrecord HttpServer [port]
  component/Lifecycle

  (start [component]
    (println ";; Starting http server at" port)
    (if (:server component)
      component
      (let [routes (find-routes component)
            server (web/run
                     (::http/servlet
                       (http/create-servlet
                         {:env                 :prod
                          ::http/routes        routes
                          ::http/resource-path "/public"
                          ::http/type          :immutant
                          ::http/port          port}))
                     :port port)]
        (assoc component :server server))))

  (stop [component]
    (println ";; Stopping http server at" port)

    (update component :server (fn [srv] (web/stop srv)))))

(defn http-server [options]
  (map->HttpServer options))
