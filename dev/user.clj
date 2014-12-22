(ns user
  (:require [clojure.repl :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.java.io :as io]
            [environ.core :refer [env]]
            [reloaded.repl :refer [system init start stop go reset]]
            ;;[ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [prone.middleware :as prone]
            [compojure.handler :refer [api]]
            [natsume-server.system :as system]
            [natsume-server.nlp.evaluation :as e]))

(def config
  {:db {:subname "//localhost:5432/natsumedev"
        :user "natsumedev"
        :password "riDJMq98LpyWgB7F"}
   :http {:bind-address "0.0.0.0"
          :bind-port 3000
          :run true}
   :log {:directory "./log"}
   :dirs ["/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OT"] ;; 26.067740016833334 m
   :verbose true
   :clean   false #_true
   :process false #_true
   :search  false #_true
   :sampling {:ratio 0.0
              :seed 2
              :replace false
              :hold-out false}
   :app {:middleware [prone/wrap-exceptions api]}})

(when (io/resource "local.clj")
  (load "local"))

(reloaded.repl/set-init! #(system/new-system config))
