(ns natsume-server.models.dsl
  (:require [clojure.string :as string]
            [honeysql.core :as h]
            [honeysql.format :as fmt]
            [honeysql.helpers :refer :all :exclude [update]]
            [clojure.core.strint :refer [<<]]))

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
