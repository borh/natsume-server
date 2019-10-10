(ns natsume-server.config
  (:require [mount.core :refer [defstate]]
            [aero.core :as aero]))

(defstate config
  :start (aero/read-config (clojure.java.io/resource "config.edn") {:profile :server})
  :stop :stopped)

(defstate secrets
  :start (aero/read-config (clojure.java.io/resource "secrets.edn"))
  :stop :stopped)
