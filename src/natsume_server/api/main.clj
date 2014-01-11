(ns natsume-server.api.main
  (:require [natsume-server.config :as cfg]

            [io.pedestal.service-tools.server :as server]
            [natsume-server.api.service :as service]
            [io.pedestal.service-tools.dev :as dev]
            [io.pedestal.service.http :as bootstrap]))

(defn run-dev
  "The entry-point for 'lein run-dev'"
  [& args]
  (dev/init service/service #'service/routes)
  (apply dev/-main args))

;; To implement your own server, copy io.pedestal.service-tools.server and
;; customize it.
;;;;(def service-instance
;;;;  "Global var to hold service instance."
;;;;  nil)

(defn start-server!
  "The entry-point for 'lein run'"
  [& args]
  (server/init service/service)
  (apply server/-main args))

;; Fns for use with io.pedestal.servlet.ClojureVarServlet

(defn servlet-init [this config]
  (server/init service/service)
  (server/servlet-init this config))

(defn servlet-destroy [this]
  (server/servlet-destroy this))

(defn servlet-service [this servlet-req servlet-resp]
  (server/servlet-service this servlet-req servlet-resp))

;;(defn create-server
;;  "Standalone dev/prod mode."
;;  [& [opts]]
;;  (alter-var-root #'service-instance
;;                  (constantly (bootstrap/create-server (merge service/service opts)))))
;;
;;;; Replacing -main
;;(defn start-server! [& args]
;;  (create-server)
;;  (bootstrap/start service-instance))
;;
;;;; Container prod mode for use with the io.pedestal.servlet.ClojureVarServlet class.
;;
;;(defn servlet-init [this config]
;;  (alter-var-root #'service-instance
;;                  (constantly (bootstrap/create-servlet service/service)))
;;  (.init (::bootstrap/servlet service-instance) config))
;;
;;(defn servlet-destroy [this]
;;  (alter-var-root #'service-instance nil))
;;
;;(defn servlet-service [this servlet-req servlet-resp]
;;  (.service ^javax.servlet.Servlet (::bootstrap/servlet service-instance)
;;            servlet-req servlet-resp))
