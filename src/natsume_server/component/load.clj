(ns natsume-server.component.load
  (:require [clojure.spec.alpha :as s]

            [natsume-server.models.corpus-specs]
            [natsume-server.component.database :as db :refer [connection database-init]]
            [natsume-server.component.persist :as persist]
            [natsume-server.config :refer [config]]
            [natsume-server.utils.fs :refer [base-name walk-path]]

            [plumbing.core :refer [map-keys fnk defnk ?>>]]
            [mount.core :refer [defstate]]
            [datoteka.core :as fs]))

(defn export-format [{:keys [corpus paragraphs]}])

(defn extract-corpus! [sampling corpus-dir extraction-unit extraction-features extraction-file])

(s/fdef process-directories
  :args (s/cat :dirs (s/coll-of string?))
  :ret :corpus/files)

(defn process-directories
  "Processes directories to check if they exist and returns a set of io/file directory objects with canonical and normalized paths."
  [dirs]
  (if (seq dirs)
    (into #{}
          (comp (map fs/path)
                (map fs/normalize)
                (filter fs/directory?))
          dirs)))

(s/def :sampling/ratio (s/and double? #(>= 1.0 % 0.0)))
(s/def :sampling/seed int?)
(s/def :sampling/hold-out boolean?)
(s/def :sampling/replace boolean?)
(s/def ::sampling (s/keys :req-un [:sampling/ratio :sampling/seed :sampling/hold-out :sampling/replace]))
(s/fdef process
  :args (s/cat :conn :database/datasource
               :dirs (s/coll-of string?)
               :sampling ::sampling)
  :ret nil?)

(defn process
  "Initializes database and processes corpus directories from input.
  If no corpus directory is given or the -h flag is present, prints
  out available options and arguments."
  [dirs sampling]
  ((comp dorun map) (partial persist/persist-corpus! sampling) (process-directories dirs)))

(s/fdef extract
  :args (s/cat :dirs (s/coll-of string?)
               :sampling ::sampling
               :extraction-unit keyword?
               :extraction-features keyword?
               :extraction-file string?)
  :ret nil?)

(defn extract
  "Initializes database and processes corpus directories from input.
  If no corpus directory is given or the -h flag is present, prints
  out available options and arguments."
  [dirs sampling extraction-unit extraction-features extraction-file]
  (doseq [dir (process-directories dirs)]
    (extract-corpus! sampling dir extraction-unit extraction-features extraction-file)))

(defstate data
  :start (let [{:keys [dirs sampling search]} config]
           (when (:process config)
             (process dirs sampling)
             ;; Make sure to free all instances of CaboCha after processing.
             (natsume-server.nlp.cabocha-wrapper/reset-threadpool!)
             (System/gc))
           (when search
             (db/create-search-tables!))))
