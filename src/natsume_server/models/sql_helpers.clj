(ns natsume-server.models.sql-helpers
  (:require [honeysql.core :as h]
            [honeysql.format :as fmt]
            [honeysql.helpers :refer :all]
            [clojure.java.jdbc :as j]
            [clojure.string :as string]
            [clojure.set :as set]
            [clojure.core.strint :refer [<<]]
            [clojure.core.cache :as cache]
            [clj-configurator.core :refer [defconfig env props]]
            [clojure.tools.reader.edn :as edn]
            [natsume-server.utils.naming :refer [dashes->underscores underscores->dashes]]
            [plumbing.core :refer [?> ?>> map-keys]])
  (:import [org.postgresql.util PGobject]
           [org.postgresql.jdbc4 Jdbc4Array]
           [java.sql PreparedStatement]
           [com.alibaba.druid.pool DruidDataSource]))

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
;;     CREATE TABLESPACE fastspace LOCATION '/media/ssd-fast/postgresql/data';
;;     CREATE DATABASE natsumedev WITH OWNER natsumedev ENCODING 'UNICODE' TABLESPACE fastspace;
;;
;; Setting the tablespace is optional.
;;
;; Then switching to the database as postgres user, add the following extensions:
;;
;;     CREATE EXTENSION ltree;
;;
;; :subname, :user and :password should match that found in the following dbspec:
(def db-spec
  (merge
   {:classname   "org.postgresql.Driver"
    :subprotocol "postgresql"}
   settings))

(defn druid-pool
  [spec]
  (let [cpds (doto (DruidDataSource.)
               (.setDriverClassName (:classname spec))
               (.setUrl (str "jdbc:" (:subprotocol spec) ":" (:subname spec)))
               (.setUsername (:user spec))
               (.setPassword (:password spec))
               (.setValidationQuery "SELECT 'x'")
               (.setMaxActive 80))]
    {:datasource cpds}))

(def druid-pooled-db (delay (druid-pool db-spec)))
(defn druid-db-connection [] @druid-pooled-db)

;; # SQL Utilities

(defmacro with-db
  [& body]
  `(try
     (j/with-db-connection (druid-db-connection)
       (do ~@body)
       #_(j/with-naming-strategy {:entity dashes->underscores :keyword underscores->dashes}
         (do ~@body)))
     (catch java.sql.SQLException e# (j/print-sql-exception-chain e#))))

(defn make-jdbc-array
  "Creates a JDBC array from sequence.
  Needs an open JDBC connection to work."
  ([db aseq]
     (if-let [^java.sql.Connection con (j/db-find-connection db)]
       (.createArrayOf ^java.sql.Connection con "text" (into-array String (map name aseq)))
       (with-open [^java.sql.Connection con (j/get-connection db)]
         (.createArrayOf ^java.sql.Connection con "text" (into-array String (map name aseq))))))
  ([aseq]
     (.createArrayOf ^java.sql.Connection (j/get-connection (druid-db-connection)) "text" (into-array String (map name aseq)))))

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

;; SQL DSL

(defmethod fmt/fn-handler "tilda"
  [_ field value]
  (str (fmt/to-sql field)
       " ~ '"
       (string/join "." value)
       "'"))

(defmethod fmt/fn-handler "as-integer"
  [_ field]
  (str (fmt/to-sql field)
       "::integer"))

;; FIXME needs to be called by format-predicate
(defmethod fmt/fn-handler "||_||"
  [_ [& fields] & field-name]
  (str (fmt/paren-wrap (string/join " || '_' || "
                                    (map fmt/to-sql fields)))
       (if field-name (str " AS " (fmt/to-sql field-name)))))

(comment
  (defmethod fmt/fn-handler "as"
    [_ field field-name]
    (str (fmt/to-sql field)
         " AS "
         (fmt/to-sql field-name)))
  (defmethod fmt/format-clause :as
    [[_ [field field-name]] sql-map]
    (str (fmt/to-sql field)
         " AS "
         (fmt/to-sql field-name)))
  (defhelper as [m args]
    (assoc m :as args)))

(defn sql-cast [sql type]
  (h/call :cast (h/raw (str (fmt/to-sql sql) " AS " (fmt/to-sql type)))))

(defmethod fmt/format-clause :create-table-as [[_ [tbl-name pred]] sql-map]
  (str "CREATE TABLE "
       (fmt/to-sql tbl-name)
       " AS "
       (fmt/to-sql pred)))
(defhelper create-table-as [m args]
  (assoc m :create-table-as args))

(defmethod fmt/format-clause :create-unlogged-table-as [[_ [tbl-name pred]] sql-map]
  (str "CREATE UNLOGGED TABLE "
       (fmt/to-sql tbl-name)
       " AS "
       (fmt/to-sql pred)))
(defhelper create-unlogged-table-as [m args]
  (assoc m :create-unlogged-table-as args))

(defmethod fmt/format-clause :rename-table [[_ [prev new]] sql-map]
  (<< "ALTER TABLE ~(fmt/to-sql prev) RENAME TO ~(fmt/to-sql new)"))
(defhelper rename-table [m args]
  (assoc m :rename-table args))

(comment :TODO
  (defmethod fmt/format-clause :insert [[_ [tbl-name pred]] sql-map]
    (<< "INSERT INTO ~(fmt/to-sql tbl-name) VALUES ")))

(defmethod fmt/format-clause :add-fk [[_ [tbl target-tbl target-col]] sql-map]
  (<< "ALTER TABLE ~(fmt/to-sql tbl) ADD CONSTRAINT idx_~(fmt/to-sql tbl)_~(fmt/to-sql target-tbl)_~(fmt/to-sql target-col)_fk FOREIGN KEY (~(fmt/to-sql target-tbl)_~(fmt/to-sql target-col)) REFERENCES ~(fmt/to-sql target-tbl) (~(fmt/to-sql target-col))"))
(defhelper add-fk [m args]
  (assoc m :add-fk args))

(defmethod fmt/fn-handler "union" [_ & preds]
  (string/join " UNION " (map h/format preds)))
(defmethod fmt/format-clause :union [[& preds] sql-map]
  (println preds)
  (string/join " UNION " (map fmt/to-sql preds)))
(defhelper union [m args]
  (assoc m :union args))

(defmethod fmt/format-clause :create-index [[_ [tbl-name col-name & idx-type]] sql-map]
  (let [type (fmt/to-sql (or (first idx-type) :btree))]
    (<< "CREATE INDEX idx_~(fmt/to-sql tbl-name)_~(fmt/to-sql col-name)_~{type} ON ~(fmt/to-sql tbl-name) USING ~{type}(~(fmt/to-sql col-name))")))
(defhelper create-index [m args]
  (assoc m :create-index args))

(defmethod fmt/format-clause :drop-index [[_ [tbl-name col-name & idx-type]] sql-map]
  (let [type (fmt/to-sql (or (first idx-type) :btree))]
    (<< "DROP INDEX IF EXISTS idx_~(fmt/to-sql tbl-name)_~(fmt/to-sql col-name)_~{type} CASCADE")))

(defn seq->sql-array [aseq]
  (format "'{\"%s\"}'" (->> aseq
                            (map name)
                            (string/join "\", \"" ))))

;; ## Database wrapper functions
(defn q
  "Wrapper function for query that sets default name transformation and optional result (row-level) transformation functions."
  [q & trans]
  (j/query (druid-db-connection)
           (h/format q)
           :row-fn (if trans
                     (reduce comp trans)
                     identity)
           :identifiers underscores->dashes
           :entities dashes->underscores))

;; Memoized q using LRU (Least Recently Used) strategy.
(def query-cache
  (atom (cache/lru-cache-factory {} :threshold 50000)))
(defn qm
  [query & trans]
  (if (cache/has? @query-cache query)
    (get (swap! query-cache #(cache/hit % query)) query)
    (let [new-value (apply q query trans)]
      (swap! query-cache #(cache/miss % query new-value))
      new-value)))

;; FIXME: ugly hack to get into internals of clojure.java.jdbc
;; begin clojure.java.jdbc
(defn- extract-transaction?
  "Given a sequence of data, look for :transaction? arg in it and return a pair of
   the transaction? value (defaulting to true) and the data without the option."
  [data]
  (let [before (take-while (partial not= :transaction?) data)
        after  (drop-while (partial not= :transaction?) data)]
    (if (seq after)
      [(second after) (concat before (nnext after))]
      [true data])))
(defn- set-parameters
  "Add the parameters to the given statement."
  [^PreparedStatement stmt params]
  (dorun (map-indexed (fn [ix value]
                        (.setObject stmt (inc ix) value))
                      params)))
(defn- throw-non-rte
  "This ugliness makes it easier to catch SQLException objects
  rather than something wrapped in a RuntimeException which
  can really obscure your code when working with JDBC from
  Clojure... :("
  [^Throwable ex]
  (cond (instance? java.sql.SQLException ex) (throw ex)
        (and (instance? RuntimeException ex) (.getCause ex)) (throw-non-rte (.getCause ex))
        :else (throw ex)))
(defn db-do-prepared-return-keys
  "Executes an (optionally parameterized) SQL prepared statement on the
  open database connection. The param-group is a seq of values for all of
  the parameters.
  Return the generated keys for the (single) update/insert."
  [db transaction? sql param-group]
  (if-let [^java.sql.Connection con (j/db-find-connection db)]
    (with-open [^PreparedStatement stmt (j/prepare-statement con sql :return-keys true)]
      (set-parameters stmt param-group)
      (letfn [(exec-and-return-keys []
                (let [counts (.executeUpdate stmt)]
                  (try
                    (let [rs (.getGeneratedKeys stmt)
                          result (first (j/result-set-seq rs))]
                      ;; sqlite (and maybe others?) requires
                      ;; record set to be closed
                      (.close rs)
                      result)
                    (catch Exception _
                      ;; assume generated keys is unsupported and return counts instead:
                      counts))))]
        (if transaction?
          (j/with-db-transaction [t-db (j/add-connection db (.getConnection stmt))]
                          (exec-and-return-keys))
          (try
            (exec-and-return-keys)
            (catch Exception e
              (throw-non-rte e))))))
    (with-open [^java.sql.Connection con (j/get-connection db)]
      (db-do-prepared-return-keys (j/add-connection db con) transaction? sql param-group))))
(defn- multi-insert-helper
  "Given a (connected) database connection and some SQL statements (for multiple
   inserts), run a prepared statement on each and return any generated keys.
   Note: we are eager so an unrealized lazy-seq cannot escape from the connection."
  [db stmts]
  (doall (map (fn [row]
                (db-do-prepared-return-keys db false (first row) (map #(if (set? %) (make-jdbc-array db (remove nil? %)) %) (rest row)))) ;; MODIFIED
              stmts)))
(defn- insert-helper
  "Given a (connected) database connection, a transaction flag and some SQL statements
   (for one or more inserts), run a prepared statement or a sequence of them."
  [db transaction? stmts]
  (if (string? (first stmts))
    (apply j/db-do-prepared db transaction? (first stmts) (rest stmts))
    (if transaction?
      (j/with-db-transaction [t-db db] (multi-insert-helper t-db stmts))
      (multi-insert-helper db stmts))))
(defn insert!*
  "Given a database connection, a table name and either maps representing rows or
   a list of column names followed by lists of column values, perform an insert.
   Use :transaction? argument to specify whether to run in a transaction or not.
   The default is true (use a transaction)."
  [db table & options]
  ;;(println db)
  (let [[transaction? maps-or-cols-and-values-etc] (extract-transaction? options)
        stmts (apply j/insert-sql table maps-or-cols-and-values-etc)]
    (if-let [con (j/db-find-connection db)]
      (insert-helper db transaction? stmts)
      (with-open [^java.sql.Connection con (j/get-connection db)]
        (insert-helper (j/add-connection db con) transaction? stmts)))))
;; end clojure.java.jdbc

(defn i!*
  [tbl-name row-fn & rows]
  (let [rowseq (->> rows flatten (map #(->> %
                                            row-fn
                                            (map-keys dashes->underscores))))]
    (try (apply j/insert! (druid-db-connection) (dashes->underscores tbl-name) rowseq)
         (catch Exception e (do (j/print-sql-exception-chain e) (println rowseq))))))

(defn i!
  [tbl-name & rows]
  (let [rowseq (->> rows flatten (map #(map-keys dashes->underscores %)))]
    (try (apply insert!* (druid-db-connection) (dashes->underscores tbl-name) rowseq)
         (catch Exception e (do (j/print-sql-exception-chain e) (println rowseq))))))

(defn u!
  [tbl-name new-val where-clause]
  (j/update! (druid-db-connection)
             tbl-name
             new-val
             where-clause))

(defn e!
  [sql-params & trans]
  (j/execute! (druid-db-connection)
              (map (if trans trans identity) sql-params)))

(comment
 (defn seq-execute-tx!
   [& sql-stmts]
   (with-db
     (apply j/db-do-commands (mapcat #(mapcat h/format %)
                                     sql-stmts)))))

(defn seq-execute!
  [& sql-stmts]
  (doseq [stmt (mapcat #(mapcat h/format %)
                       sql-stmts)]
    (e! [stmt])))

(defn seq-print-sql
  [& sql-stmts]
  (string/join ";\n" (mapcat #(mapcat h/format %)
                             sql-stmts)))

(defn genre-ltree-transform [m]
  (if-let [genre (:genre m)]
    (assoc m :genre (ltree->seq genre))
    m))

(defn map->and-query [qs]
  (reduce-kv #(conj %1 [:= %2 %3]) [:and] qs))
