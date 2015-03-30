(ns user
  (:require [clojure.repl :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.java.io :as io]
            [environ.core :refer [env]]
            [meta-merge.core :refer [meta-merge]]
            [natsume-server.config :as config]
            [reloaded.repl :refer [system init start stop go reset]]
            [compojure.handler :refer [api]]
            [natsume-server.system :as system]
            [natsume-server.nlp.evaluation :as e]))

(def config
  (meta-merge
    config/defaults
    config/environ
    {:db       {:subname  "//localhost:5432/natsumedev"
                :user     "natsumedev"
                :password "riDJMq98LpyWgB7F"}
     :http     {:port 3000}
     :log      {:directory "./log"}
     :dirs     ["/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OW" "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OM"] ;; 26.067740016833334 m
     :verbose  true
     :clean    false #_true
     :process  false #_true
     :search   false #_true
     :server   true                                         ;; TODO
     :sampling {:ratio    0.02
                :seed     2
                :replace  false
                :hold-out false}}))

(when (io/resource "local.clj")
  (load "local"))

(reloaded.repl/set-init! #(system/new-system config))
