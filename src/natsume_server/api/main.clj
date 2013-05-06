(ns natsume-server.api.main
  (:require [natsume-server.config :as cfg]

            [natsume-server.api.service :as service]
            [io.pedestal.service.http :as bootstrap]))

(def service-instance
  "Global var to hold service instance."
  nil)

(defn create-server
  "Standalone dev/prod mode."
  [& [opts]]
  (alter-var-root #'service-instance
                  (constantly (bootstrap/create-server (merge service/service opts {:env :dev})))))

;; Replacing -main
(defn start-server! [& args]
  (create-server)
  (bootstrap/start service-instance))

(defn stop-server!
  []
  (bootstrap/stop service-instance))

(defn restart-server!
  []
  (stop-server!)
  (start-server!))

;; Container prod mode for use with the io.pedestal.servlet.ClojureVarServlet class.

(defn servlet-init [this config]
  (alter-var-root #'service-instance
                  (constantly (bootstrap/create-servlet service/service)))
  (.init (::bootstrap/servlet service-instance) config))

(defn servlet-destroy [this]
  (alter-var-root #'service-instance nil))

(defn servlet-service [this servlet-req servlet-resp]
  (.service ^javax.servlet.Servlet (::bootstrap/servlet service-instance)
            servlet-req servlet-resp))
