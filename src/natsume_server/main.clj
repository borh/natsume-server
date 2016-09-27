(ns natsume-server.main
  (:gen-class)
  (:require
   [natsume-server.config :refer [config]]
   [natsume-server.component.database]
   [natsume-server.component.server]
   [natsume-server.component.load]
   [natsume-server.endpoint.api]
   [natsume-server.nlp.word2vec]
   [natsume-server.nlp.topic-model]
   [natsume-server.component.logging :refer [with-logging-status]]
   [aero.core :as aero]
   [mount.core :as mount]))

(defn -main [& args]
  (clojure.pprint/pprint {:new-config natsume-server.config/config})
  (with-logging-status)
  (mount/start #'natsume-server.component.database/connection)

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
     #'natsume-server.endpoint.api/api-routes
     #'natsume-server.component.server/server)))

(defn run-with-profile [profile dev?]
  (let [config (-> (aero/read-config "config.edn" {:profile profile})
                   (assoc :verbose dev?)
                   (update-in [:sampling :ratio] (fn [m] (if dev? (:dev m) (:prod m)))))]
    (clojure.pprint/pprint {:runtime-config config})
    (-> (mount/only #{#'natsume-server.config/config})
        (mount/swap {#'natsume-server.config/config config})
        (mount/start))
    (-main)))
