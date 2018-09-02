(ns natsume-server.main
  (:gen-class)
  (:require
   [mount.core :as mount]
   [natsume-server.config :refer [config]]
   [natsume-server.component.database]
   [natsume-server.component.sente]
   [natsume-server.component.server]
   [natsume-server.component.load]
   [natsume-server.endpoint.api]
   [natsume-server.nlp.word2vec]
   [natsume-server.nlp.topic-model]
   [natsume-server.component.logging :refer [with-logging-status]]
   [clojure.tools.cli :refer [parse-opts]]
   [clojure.string :as string]
   [aero.core :as aero]
   [taoensso.timbre :as timbre]
   [taoensso.timbre.appenders.core :as appenders]))

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

(def cli-options
  [["-c" "--config CONFIG" "Use EDN config file"]
   ["-p" "--profile PROFILE" "Profile (server|load)"
    :default :server
    :parse-fn keyword
    :validate [#{:server :load} "Must be either 'server' or 'load'"]]
   ["-d" "--[no-]dev" "Development mode" :default false]
   ["-e" "--extract" "Dataset extraction to local files" :default false]
   ["-u" "--unit UNIT" "(Extraction only) unit to extract (text|suw|unigrams)"
    :default :unigrams
    :parse-fn keyword
    :validate [#{:text :suw :unigrams} "Must be one of 'text', 'suw', or 'unigrams'"]]
   ["-f" "--features FEATURES" "(Extraction only) features to extract from tokens (orth|lemma)"
    :default :orth
    :parse-fn keyword
    :validate [#{:orth :lemma :orth-base} "Must be one of 'orth' or 'lemma'"]]
   ["-o" "--out FILENAME" "(Extraction only) output filename (default: corpus-extracted.tsv)"
    :default "corpus-extracted.tsv"]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["Natsume Server"
        ""
        "Usage: clojure -Ajvm -m natsume-server.main [options]"
        ""
        "Options:"
        options-summary]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}

      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}

      :else {:options options})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn run-with-profile [{:keys [config profile dev extract unit features out]}]
  (let [config (-> (aero/read-config (or config "config.edn") {:profile profile})
                   (update :log-level (fn [level] (or level (and dev :debug) :error)))
                   #_(assoc :profile (if dev? :dev :prod))
                   (update-in [:http :access-control-allow-origin] (fn [m] (if dev (:dev m) (:prod m))))
                   (update-in [:sampling :ratio] (fn [m] (if dev (:dev m) (:prod m)))))]
    (timbre/set-level! (:log-level config))
    (timbre/merge-config!
     {:appenders {:spit (assoc (appenders/spit-appender {:fname (:logfile config)})
                               :min-level :debug)}})
    (timbre/debugf "Running system with profile %s in %s mode (extraction %s)" profile (if dev "dev" "prod") (if extract "on" "off"))
    (timbre/debug {:runtime-config config})
    (-> (mount/only #{#'natsume-server.config/config})
        (mount/swap {#'natsume-server.config/config config})
        (mount/start))
    (if extract
      (do (natsume-server.component.load/extract (:dirs config) (:sampling config) unit features out)
          (System/exit 0))
      (start!))))

(defn -main [& args]
  (let [{:keys [options exit-message ok?]} (validate-args args)]
    (println options)
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (run-with-profile options))))
