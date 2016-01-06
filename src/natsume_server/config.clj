(ns natsume-server.config
  (:require [environ.core :refer [env]]))

(def defaults
  ^:displace {:http {:port 3000 :pretty-print? true}
              :db   {:password "riDJMq98LpyWgB7F",
                     :user "natsumedev",
                     :subname "//localhost:5432/natsumedev"},})

(def environ
  {:http {:port (some-> env :port Integer.)}})
