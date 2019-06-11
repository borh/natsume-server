(ns natsume-server.models.pg-types
  (:require [clojure.string :as string]
            [clojure.java.jdbc :as jdbc]
            [natsume-server.utils.naming :as naming]
            [next.jdbc.prepare :refer [SettableParameter]]
            [next.jdbc.result-set :as result-set :refer [ReadableColumn]])
  (:import [org.postgresql.util PGobject]
           [java.sql PreparedStatement Array ResultSetMetaData ResultSet]
           [clojure.lang PersistentHashSet Keyword PersistentVector]))

(set! *warn-on-reflection* true)

(defn seq->ltree
  "Converts a sequence into a PostgreSQL ltree object.
  The ltree data type is picky and supports Unicode minus punctuation and some JIS codes."
  [fields]
  (doto (PGobject.)
    (.setType "ltree")
    (.setValue
      (string/join "."
                   (->> fields
                        (remove empty?)
                        (reduce #(if (not= (peek %1) %2) (conj %1 %2) %1) [])
                        (map #(string/replace % #"(\p{P}|\s|→)+" "_")))))))

(defn ltree->seq
  "Converts a PostgreSQL ltree object into a sequence."
  [^PGobject pgobj]
  (-> pgobj
      .toString
      (string/split #"\.")))

(defn get-unqualified-column-names-kebab
  "Given `ResultSetMetaData`, return a vector of unqualified column names coverted to kebab-case."
  [^ResultSetMetaData rsmeta opts]
  (mapv (fn [^Integer i] (naming/underscores->dashes (.getColumnLabel rsmeta i)))
        (range 1 (inc (.getColumnCount rsmeta)))))

(defn as-kebab-maps
  "Given a `ResultSet` and options, return a `RowBuilder` / `ResultSetBuilder`
  that produces bare vectors of hash map rows, with simple keys."
  [^ResultSet rs opts]
  (let [rsmeta (.getMetaData rs)
        cols (get-unqualified-column-names-kebab rsmeta opts)]
    (result-set/->MapResultSetBuilder rs rsmeta cols)))

;; Extend insertion protocol: vectors as ltrees, sets as arrays.
(extend-protocol SettableParameter
  Keyword
  (set-parameter [v ^PreparedStatement s ^long i]
    (.setObject s i (name v)))

  PersistentVector
  (set-parameter [v ^PreparedStatement s ^long i]
    (.setObject s i (seq->ltree v)))

  PersistentHashSet
  (set-parameter [v ^PreparedStatement s ^long i]
    (let [conn (.getConnection s)
          meta (.getParameterMetaData s)
          type-name (.getParameterTypeName meta i)]
      (if-let [elem-type (when (= (first type-name) \_) (string/join (rest type-name)))]
        (.setObject s i (.createArrayOf conn elem-type (to-array (map name v))))
        (.setObject s i (.createArrayOf conn "text" (to-array (map name v))))))))

;; Extend read protocol to convert arrays to sets.
(extend-protocol ReadableColumn
  Array
  (read-column-by-label [x _] (into #{} (.getArray x)))
  (read-column-by-index [x _2 _3] (into #{} (.getArray x)))

  ;; Currently the only unknown PostgreSQL type in use is ltree, so we convert unconditionally.
  ;; (PGobject is the type given to unknown/nonstandard SQL types)
  PGobject
  (read-column-by-label [x _] (ltree->seq x))
  (read-column-by-index [x _2 _3] (ltree->seq x)))
