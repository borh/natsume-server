(ns user
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :as tn]
            [mount.core :as mount]
            [mount.tools.graph :refer [states-with-deps]]
            [natsume-server.config :as config :refer [run-mode config]]
            [natsume-server.component.database :as db]
            [natsume-server.component.server :as server]
            [natsume-server.component.load :as load]
            [natsume-server.endpoint.api :as api]
            [natsume-server.nlp.word2vec]
            [natsume-server.nlp.topic-model]
            [natsume-server.component.logging :refer [with-logging-status]]))

(defn start []
  (with-logging-status)
  (mount/start #'natsume-server.config/run-mode
               #'natsume-server.config/config

               #'natsume-server.component.database/connection)

  (when (:clean config)
    (mount/start
     #'natsume-server.component.database/database-init))

  (when (:server config)
    (mount/start
     #'natsume-server.component.database/!norm-map
     #'natsume-server.component.database/!genre-names
     #'natsume-server.component.database/!genre-tokens-map
     #'natsume-server.component.database/!gram-totals
     #'natsume-server.component.database/!gram-types
     #'natsume-server.component.database/!tokens-by-gram
     #'natsume-server.nlp.word2vec/!word2vec-models
     #'natsume-server.nlp.topic-model/!topic-models))

  (when (:process config)
    (mount/start
     #'natsume-server.component.load/data))

  (when (:server config)
    (mount/start
     #'natsume-server.endpoint.api/pretty
     #'natsume-server.endpoint.api/api-routes
     #'natsume-server.component.server/server)))

(defn stop []
  (mount/stop))

(defn refresh []
  (stop)
  (tn/refresh))

(defn refresh-all []
  (stop)
  (tn/refresh-all))

(defn go
  "starts all states defined by defstate"
  []
  (start)
  :ready)

(defn reset
  "stops all states defined by defstate, reloads modified source files, and restarts the states"
  []
  (stop)
  (tn/refresh :after 'user/go))

(mount/in-clj-mode)
