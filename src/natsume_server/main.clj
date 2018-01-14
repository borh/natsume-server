(ns natsume-server.main
  (:gen-class)
  (:require
   [natsume-server.config :refer [config]]
   [natsume-server.component.database]
   [natsume-server.component.sente]
   [natsume-server.component.server]
   [natsume-server.component.load]
   [natsume-server.endpoint.api]
   [natsume-server.nlp.word2vec]
   [natsume-server.nlp.topic-model]
   [natsume-server.component.logging :refer [with-logging-status]]
   [aero.core :as aero]
   [taoensso.timbre :as timbre]
   [taoensso.timbre.appenders.core :as appenders]
   [mount.core :as mount]))

(defn start! []
  (clojure.pprint/pprint {:new-config natsume-server.config/config})
  (with-logging-status)
  (mount/start #'natsume-server.config/secrets)
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
     #'natsume-server.component.sente/channel
     #'natsume-server.endpoint.api/api-routes
     #'natsume-server.component.server/server)))

(defn run-with-profile [profile dev? extract? extraction-unit extraction-features extraction-file]
  (let [config (-> (aero/read-config "config.edn" {:profile profile})
                   (update :log-level (fn [level] (or level (and dev? :debug) :error)))
                   #_(assoc :profile (if dev? :dev :prod))
                   (update-in [:http :access-control-allow-origin] (fn [m] (if dev? (:dev m) (:prod m))))
                   (update-in [:sampling :ratio] (fn [m] (if dev? (:dev m) (:prod m)))))]
    (timbre/set-level! (:log-level config))
    (timbre/merge-config!
     {:appenders {:spit (assoc (appenders/spit-appender {:fname (:logfile config)})
                               :min-level :debug)}})
    (timbre/debugf "Running system with profile %s in %s mode (extraction %s)" profile (if dev? "dev" "prod") (if extract? "on" "off"))
    (timbre/debug {:runtime-config config})
    (-> (mount/only #{#'natsume-server.config/config})
        (mount/swap {#'natsume-server.config/config config})
        (mount/start))
    (if extract?
      (do (natsume-server.component.load/extract (:dirs config) (:sampling config) extraction-unit extraction-features extraction-file)
          (System/exit 0))
      (start!))))

(defn -main [& args]
  (mount/in-cljc-mode)
  (run-with-profile :server false false :suw [:orth] ""))
