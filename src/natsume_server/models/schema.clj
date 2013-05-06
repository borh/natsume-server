(ns natsume-server.models.schema
  (:require [clojure.string :as string]
            [clojure.java.jdbc :as sql]
            [clj-configurator.core :refer [defconfig env props]]
            [clojure.tools.reader.edn :as edn]

            [natsume-server.utils.naming :refer [dashes->underscores underscores->dashes]]))

;; ## Settings
;;
;; Settings are read from local-config.clj, Java properties and from
;; environmental variables.

(defconfig settings
  :defaults {:subname  "//localhost:5432/natsumedev"
             :user     "natsumedev"
             :password ""}
  :sources [(edn/read-string (slurp "local-config.clj")) env props])

;; ## PostgreSQL setup.
;;
;; Needs the following to be set up on the PostgreSQL server:
;;
;;     CREATE USER natsumedev WITH NOSUPERUSER NOCREATEDB ENCRYPTED PASSWORD '';
;;     CREATE DATABASE natsumedev WITH OWNER natsumedev ENCODING 'UNICODE';
;;
;; Then switching to the database as postgres user, add the following extensions:
;;
;;     CREATE EXTENSION ltree;
;;
;; :subname, :user and :password should match that found in the following dbspec:
(def dbspec
  (merge
   {:classname   "org.postgresql.Driver"
    :subprotocol "postgresql"}
   settings))

;; ## JDBC naming strategy

(defn dashes->underscores [str]
  (let [result (-> str
                   name
                   (string/replace \- \_)
                   string/lower-case)]
    (if (keyword? str)
      (keyword result)
      result)))

(defn underscores->dashes [str]
  (let [result (-> str
                   name
                   (string/replace \_ \-)
                   string/lower-case)]
    (if (keyword? str)
      (keyword result)
      result)))

(def naming-strategy ; JDBC
  {:entity dashes->underscores :keyword underscores->dashes})

;; ## Database wrapper functions

(defn with-db
  [f]
  (sql/with-connection dbspec
    (sql/with-naming-strategy naming-strategy
      (f))))

(defn with-db-tx
  [f]
  (sql/with-connection dbspec
    (sql/with-naming-strategy naming-strategy
      (sql/transaction (f)))))

(defn with-db-tx-ex
  [f]
  (try (sql/with-connection dbspec
         (sql/with-naming-strategy naming-strategy
           (sql/transaction (f))))
       (catch Exception e)))

(defmacro with-dbmacro [& body]
  `(try (sql/with-connection dbspec
          (sql/with-naming-strategy naming-strategy
            (sql/transaction
             (do ~@body))))
        (catch Exception e# (do (println e#)
                                #_(println (.getNextException e#))))))

;; ## Database schema

(defn- create-tables-and-indexes
  "Create tables and indexes for Natsume.

  TODO benchmark w/o indexes (i.e. create indexes only after all data has been inserted"
  []
  (do

    (let [genres-columns [[:id   "serial"      "PRIMARY KEY"]
                          [:name "varchar(48)" "UNIQUE" "NOT NULL"]]]
      (apply sql/create-table :genres genres-columns)
      (apply sql/create-table :subgenres genres-columns)
      (apply sql/create-table :subsubgenres genres-columns)
      (apply sql/create-table :subsubsubgenres genres-columns))

    ;; TODO need to add information from CopyRight_Annotation.txt as
    ;; per BCCWJ usage guidelines.
    (sql/create-table
     :sources
     [:id                 "serial"       "PRIMARY KEY"]
     [:title              "varchar(256)" "NOT NULL"]
     [:author             "varchar(256)"]
     [:year               "smallint"     "NOT NULL"]
     [:basename           "varchar(128)" "NOT NULL"]
     [:genres-id          "smallint"     "REFERENCES genres(id)"]
     [:subgenres-id       "smallint"     "REFERENCES subgenres(id)"]
     [:subsubgenres-id    "smallint"     "REFERENCES subsubgenres(id)"]
     [:subsubsubgenres-id "smallint"     "REFERENCES subsubsubgenres(id)"])
    (sql/do-commands "CREATE INDEX idx_sources_genres_id ON sources (genres_id)")
    (sql/do-commands "CREATE INDEX idx_sources_subgenres_id ON sources (subgenres_id)")
    (sql/do-commands "CREATE INDEX idx_sources_subsubgenres_id ON sources (subsubgenres_id)")
    (sql/do-commands "CREATE INDEX idx_sources_subsubsubgenres_id ON sources (subsubsubgenres_id)")

    (sql/create-table
     :sentences
     [:id                "serial"  "PRIMARY KEY"]
     [:text              "text"    "NOT NULL"]
     [:sentence-order-id "integer" "NOT NULL"]
     [:paragraph-id      "integer" "NOT NULL"]
     [:sources-id        "integer" "REFERENCES sources(id)"]
     [:type              "text"] ;; TODO Q&A, conversation, etc.
     ;; The following are the raw numbers needed to calculate readability at the sentence, paragraph or document scale
     [:length       "smallint"]
     [:hiragana     "smallint"]
     [:katakana     "smallint"]
     [:kanji        "smallint"]
     [:romaji       "smallint"]
     [:symbols      "smallint"]
     [:commas       "smallint"]
     [:japanese     "smallint"]
     [:chinese      "smallint"]
     [:gairai       "smallint"]
     [:symbolic     "smallint"]
     [:mixed        "smallint"]
     [:unk          "smallint"]
     [:pn           "smallint"]
     [:jlpt-level   "real"]
     [:bccwj-level  "real"]
     [:tokens       "smallint"]
     [:chunks       "smallint"]
     [:predicates   "smallint"]
     [:link-dist    "real"]
     [:chunk-depth  "real"])
    (sql/do-commands "CREATE INDEX idx_sentences_sources_id ON sentences (sources_id)")

    ;; Append only long format
    (sql/create-table
     :tokens
     [:sentences-id :integer "NOT NULL" "REFERENCES sentences(id)"]
     [:pos1      "varchar(8)"  "NOT NULL"]
     [:pos2      "varchar(8)"  "NOT NULL"]
     [:orthBase  "text"        "NOT NULL"]
     [:lemma     "text"        "NOT NULL"])

    (let [sentences-column [:sentences-id :integer "NOT NULL" "REFERENCES sentences(id)"]
          gram-columns (for [order (range 1 5)]
                         [[(str "string_" order) :text     "NOT NULL"]
                          [(str "pos_"    order) :text     "NOT NULL"]
                          [(str "tags_"   order) "text[]"  "NOT NULL"] ;; consider HSTORE? (for aggregate frequencies)
                          [(str "begin_"  order) :smallint "NOT NULL"]
                          [(str "end_"    order) :smallint "NOT NULL"]])]
      (apply sql/create-table :gram-2 (conj (apply concat (take 2 gram-columns)) sentences-column))
      (apply sql/create-table :gram-3 (conj (apply concat (take 3 gram-columns)) sentences-column))
      (apply sql/create-table :gram-4 (conj (apply concat (take 4 gram-columns)) sentences-column)))))

(def !genres
  (atom {}))

;; ## Clean-slate database functions

(defn- drop-all-cascade
  "Drop cascade all tables and indexes."
  []
  (sql/with-query-results stmts
    ["SELECT 'DROP TABLE \"' || tablename || '\" CASCADE' FROM pg_tables WHERE schemaname = 'public'"]
    (doseq [stmt (flatten (map vals #spy/d stmts))]
      (sql/do-commands stmt))))

;; ## Final database initialization

(defn init-database
  "Initialize Natsume database.
   Creates schema and indexes.
   Supports optional parameters:
   destructive?: recreates all tables (destructive, use with caution!)"
  [destructive?]
  (when destructive? ;; Drop and recreate all tables
    (with-db-tx drop-all-cascade)
    (reset! !genres {}))
  (with-db-tx create-tables-and-indexes))
