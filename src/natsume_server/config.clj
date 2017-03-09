(ns natsume-server.config
  (:require [aero.core :as aero]
            [mount.core :as mount :refer [defstate]]))

(defstate config
  :start (aero/read-config "config.edn" {:profile :server}))

(defstate secrets
  :start (aero/read-config "secrets.edn"))
