(ns natsume-server.models.pg-types
  (:require [clojure.string :as string]
            [clojure.java.jdbc :as jdbc])
  (:import [org.postgresql.util PGobject]
           [java.sql PreparedStatement Array]
           [clojure.lang PersistentHashSet Keyword]))

;; We extend jdbc to serialize/unserialize arrays as sets (for our tags use-case).
;; TODO do same thing for ltree!
(extend-protocol jdbc/ISQLParameter
  PersistentHashSet
  (set-parameter [v ^PreparedStatement stmt ^long i]
    (let [conn (.getConnection stmt)
          meta (.getParameterMetaData stmt)
          type-name (.getParameterTypeName meta i)]
      (if-let [elem-type (when (= (first type-name) \_) (string/join (rest type-name)))]
        (.setObject stmt i (.createArrayOf conn elem-type (to-array (map name v))))
        (.setObject stmt i (.createArrayOf conn "text" (to-array (map name v)))))))
  Keyword
  (set-parameter [v ^PreparedStatement stmt ^long i]
    (.setObject stmt i (name v))))

(extend-protocol jdbc/IResultSetReadColumn
  Array
  (result-set-read-column [val _ _]
    (into #{} (.getArray val))))

(defn seq->sql-array [aseq]
  (format "'{\"%s\"}'" (->> aseq
                            (map name)
                            (string/join "\", \"" ))))
;; # SQL Utilities

;; TODO Extend protocols like below
(defn seq->ltree
  "Converts a sequence into a PostgreSQL ltree object.
  The ltree data type is picky and supports Unicode minus punctuation and some JIS codes."
  [fields]
  (doto (PGobject.)
    (.setType "ltree")
    (.setValue (string/join "."
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
