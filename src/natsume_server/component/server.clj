(ns natsume-server.component.server
  (:require [com.stuartsierra.component :as component]
            [org.httpkit.server :refer [run-server]]))

(def ^{:dynamic true} *conn*)

(defrecord Server [app]
  component/Lifecycle

  (start [component]
    (println ";; Starting server")
    (binding [*conn* (-> app :database :connection)]
      (if (:server component)
        component
        (let [options (-> component (dissoc :app) (assoc :join? false))
              server (run-server (:handler app) options)]
          (assoc component :server server)))))

  (stop [component]
    (println ";; Stopping server")

    ;; http-kit returns a function to stop the server, so we simply call it and return nil.
    (update component :server (fn [srv] (when-not (nil? srv) (srv :timeout 1000))))))

(defn server [options database]
  (map->Server (assoc options :database database)))
