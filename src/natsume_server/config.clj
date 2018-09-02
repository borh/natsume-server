(ns natsume-server.config
  (:require [mount.core :refer [defstate]]
            [aero.core :as aero]))

(defstate config
  :start (aero/read-config "config.edn" {:profile :server})
  :stop :stopped)

(defstate secrets
  :start (aero/read-config "secrets.edn")
  :stop :stopped)
