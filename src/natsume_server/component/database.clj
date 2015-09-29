(ns natsume-server.component.database
  (:require [com.stuartsierra.component :as component]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.set :as set]
            [clojure.core.reducers :as r]
            [clojure.java.jdbc :as j]
            [java-jdbc.ddl :as ddl]
            [honeysql.core :as h]
            [honeysql.format :as fmt]
            [honeysql.helpers :refer :all :exclude [update]]
            [clojure.core.cache :as cache]
            [clojure.core.strint :refer [<<]]

            [d3-compat-tree.tree :refer [seq-to-tree normalize-tree]]

            [natsume-server.models.pg-types :refer :all]
            [natsume-server.utils.naming :refer [dashes->underscores underscores->dashes]]
            [natsume-server.models.dsl :refer :all]
            [natsume-server.nlp.stats :as stats]
            [natsume-server.nlp.text :as text]
            [natsume-server.nlp.importers.bccwj :as bccwj]
            [natsume-server.nlp.importers.wikipedia :as wikipedia]
            [natsume-server.nlp.annotation-middleware :as am]
            [natsume-server.nlp.readability :as rd]
            [natsume-server.utils.numbers :refer [compact-number]]

            [bigml.sampling.simple :as sampling]
            [plumbing.core :refer :all :exclude [update]]
            [plumbing.graph :as graph]
            [clojure.data.csv :as csv]
            [me.raynes.fs :as fs]
            [iota :as iota]

            [environ.core :refer [env]]
            [plumbing.core :refer [fn->>]]
            [schema.core :as s])
  (:import [com.alibaba.druid.pool DruidDataSource]
           [natsume_server.nlp.cabocha_wrapper Chunk]
           (java.io File)))

(defn druid-pool
  [spec]
  (let [cpds (doto (DruidDataSource.)
               (.setDriverClassName "org.postgresql.Driver")
               (.setUrl (str "jdbc:postgresql:" (:subname spec)))
               (.setUsername (:user spec))
               (.setPassword (:password spec))
               (.setValidationQuery "SELECT 'x'")
               (.setMaxActive 80))]
    {:datasource cpds}))


;; (def ^{:dynamic true} *conn*)



;; ## Database wrapper functions
(defn q
  "Wrapper function for query that sets default name transformation and optional result (row-level) transformation functions."
  [conn q & trans]
  (j/query conn
           (h/format q)
           :row-fn (if trans
                     (reduce comp trans)
                     identity)
           :identifiers underscores->dashes
           :entities dashes->underscores))

;; Memoized q using LRU (Least Recently Used) strategy.
;; TODO: consider using LU.
(def query-cache
  (atom (cache/lru-cache-factory {} :threshold 50000)))
(defn qm
  [conn query & trans]
  (let [query-trans [query trans]]
    (if (cache/has? @query-cache query-trans)
      (first (get (swap! query-cache #(cache/hit % query-trans)) query-trans))
      (let [new-value (apply q conn query trans)]
        (swap! query-cache #(cache/miss % query-trans [new-value trans]))
        new-value))))

(defn i!*
  [conn tbl-name row-fn & rows]
  (let [rowseq (->> rows flatten (map #(->> %
                                            row-fn
                                            (map-keys dashes->underscores))))]
    (try (apply j/insert! conn (dashes->underscores tbl-name) rowseq)
         (catch Exception e (do (j/print-sql-exception-chain e) (println rowseq))))))

(defn i!
  [conn tbl-name & rows]
  (let [rowseq (->> rows flatten (map #(map-keys dashes->underscores %)))]
    (try (apply j/insert! conn (dashes->underscores tbl-name) rowseq)
         (catch Exception e (do (try (j/print-sql-exception-chain e) (catch Exception e' (println e))) (println rowseq))))))

(defn u!
  [conn tbl-name new-val where-clause]
  (j/update! conn
             tbl-name
             new-val
             where-clause))

(defn e!
  [conn sql-params & trans]
  (j/execute! conn
              (map (if trans trans identity) sql-params)))

(defn db-do-commands! [conn stmt]
  (j/db-do-commands conn stmt))

(defn seq-execute!
  [conn & sql-stmts]
  (doseq [stmt (mapcat #(mapcat h/format %)
                       sql-stmts)]
    (e! conn [stmt])))

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


;; ## Clean-slate database functions

(defn- drop-all-cascade!
  "Drop cascade all tables and indexes."
  [conn]
  (let [drop-stmts
        (->> (j/query conn ["SELECT 'DROP TABLE \"' || tablename || '\" CASCADE' FROM pg_tables WHERE schemaname = 'public'"])
             (map vals)
             flatten)]
    (doseq [stmt drop-stmts]
      (j/db-do-commands conn stmt))))

;; Schema


;; ## JDBC naming strategy
(def naming-strategy ; JDBC.
  {:entity dashes->underscores :keyword underscores->dashes})

(defn create-unlogged-table-ddl
  "Given a table name and column specs with an optional table-spec
return the DDL string for creating that unlogged table."
  [name & specs]
  (let [col-specs (take-while (fn [s]
                                (not (or (= :table-spec s)
                                         (= :entities s)))) specs)
        other-specs (drop (count col-specs) specs)
        {:keys [table-spec entities] :or {entities identity}} other-specs
        table-spec-str (or (and table-spec (str " " table-spec)) "")
        specs-to-string (fn [specs]
                          (clojure.string/join
                                 (map (j/as-sql-name entities)
                                      (apply concat
                                             (interpose [", "]
                                                        (map (partial interpose " ") specs))))))]
    (format "CREATE UNLOGGED TABLE %s (%s)%s"
            (j/as-sql-name entities name)
            (specs-to-string col-specs)
            table-spec-str)))

(defn create-table [conn tbl-name & specs]
  (j/db-do-commands conn (string/replace (apply j/create-table-ddl tbl-name specs) #"-" "_")))

(defn create-unlogged-table [conn tbl-name & specs]
  (j/db-do-commands conn (string/replace (apply create-unlogged-table-ddl tbl-name specs) #"-" "_")))

(defn schema-keys [schema]
  (->> schema
       next
       (map first)))

;; ## Database schema

(def sources-schema
  [:sources
   [:id       :serial   "PRIMARY KEY"]
   [:title    :text     "NOT NULL"]
   [:author   :text]
   [:year     :smallint "NOT NULL"]
   [:basename :text     "NOT NULL"]
   [:genre    :ltree    "NOT NULL"]])

(def sentences-schema
  [:sentences
   [:id                 "serial"  "PRIMARY KEY"]
   [:text               "text"    "NOT NULL"]
   [:sentence-order-id  "integer" "NOT NULL"]
   [:paragraph-order-id "integer" "NOT NULL"]
   [:sources-id         "integer" "NOT NULL" "REFERENCES sources(id)"]
   [:tags               "text[]"]
   ;; The following are the raw numbers needed to calculate readability at the sentence, paragraph or document scale
   ;; FIXME readability should really be calculated after the whole corpus is processed: in this way, it should be possible to compute bccwj-level straight from the data, though this again depends on the existence of the tokens table as we don't want to re-parse all the data. And this might actually be really slow in SQL.
   ;; But for cleanliness reasons, separate the following from sentences for now. FIXME
   [:length      :smallint]
   [:hiragana    :smallint]
   [:katakana    :smallint]
   [:kanji       :smallint]
   [:romaji      :smallint]
   [:symbols     :smallint]
   [:commas      :smallint]
   [:japanese    :smallint]
   [:chinese     :smallint]
   [:gairai      :smallint]
   [:symbolic    :smallint]
   [:mixed       :smallint]
   [:unk         :smallint]
   [:pn          :smallint]
   [:jlpt-level  :real]
   [:bccwj-level :real]
   [:tokens      :smallint]
   [:chunks      :smallint]
   [:predicates  :smallint]
   [:link-dist   :real]
   [:chunk-depth :real]])

(def tokens-schema
  ;; TODO: It would be better to remove '*', allow NULLs and add partial indexes.
  [:tokens
   [:sentences-id :integer "NOT NULL" "REFERENCES sentences(id)"]
   [:position     :integer "NOT NULL"]
   [:pos          :text    "NOT NULL"]
   [:pos-1        :text    "NOT NULL"]
   [:pos-2        :text    "NOT NULL"]
   [:pos-3        :text    "NOT NULL"]
   [:pos-4        :text    "NOT NULL"]
   [:c-type       :text    "NOT NULL"]
   [:c-form       :text    "NOT NULL"]
   [:lemma        :text    "NOT NULL"]
   [:orth         :text    "NOT NULL"]
   [:pron         :text]
   [:orth-base    :text    "NOT NULL"]
   [:pron-base    :text]
   [:goshu        :text    "NOT NULL"]
   [:tags         "text[]"]])

;; The :unigrams table is mostly equivalent to the :gram-1 table, with
;; the exceptions (1) that it is not temporary, but persisted for later
;; reference, and (2) its tokens are indexed with position.
(def unigrams-schema
  [:unigrams
   [:sentences-id :integer "NOT NULL" "REFERENCES sentences(id)"]
   [:position     :integer "NOT NULL"]
   [:string       :text    "NOT NULL"]
   [:pos          :text    "NOT NULL"]
   [:tags         "text[]"]])

(def n-gram-schemas
  (let [sentences-column [:sentences-id :integer "NOT NULL" "REFERENCES sentences(id)"]
        gram-columns (for [order (range 1 5)]
                       [[(str "string_" order) :text     "NOT NULL"]
                        [(str "pos_"    order) :text     "NOT NULL"]
                        [(str "tags_"   order) "text[]"  "NOT NULL"] ;; consider HSTORE? (for aggregate frequencies)
                        [(str "begin_"  order) :smallint "NOT NULL"]
                        [(str "end_"    order) :smallint "NOT NULL"]])]
    (for-map [n (range 1 5)]
             (keyword (str "gram-" n))
             (concat [(keyword (str "gram-" n))] (conj (apply concat (take n gram-columns)) sentences-column)))))

(defn- create-tables-and-indexes!
  "Create tables and indexes for Natsume.

  TODO benchmark w/o indexes (i.e. create indexes only after all data has been inserted"
  [conn]
  (do

    ;; TODO need to add information from CopyRight_Annotation.txt as per BCCWJ usage guidelines.
    (apply create-table conn sources-schema)
    (e! conn (h/format (create-index :sources :genre :gist)))
    (e! conn (h/format (create-index :sources :genre :btree)))

    (apply create-table conn sentences-schema)
    (e! conn (h/format (create-index :sentences :sources-id)))
    (e! conn (h/format (add-fk :sentences :sources :id)))
    ;;(j/do-commands "CREATE INDEX idx_sentences_sources_id ON sentences (sources_id)")

    ;; Append only long format.
    (apply create-table conn tokens-schema)
    (apply create-table conn unigrams-schema)

    ;; FIXME Add Natsume unit token-like table
    ;; 2, 3 and 4 collocation gram tables.
    (doseq [[tbl-name tbl-schema] n-gram-schemas]
      (apply create-unlogged-table conn tbl-schema))))

;; ## Search Schemas
;;
;; Search schemas are created from the above tables to enable fast searching with common queries.
;; They must be created after processing.
;; TODO convert to migrations (https://github.com/macourtney/drift / https://github.com/pjstadig/migratus) or clojure.java.jdbc DSL
;; FIXME !Profile if having indexes to make new table is faster than not creating them!
;; TODO concurrent index creation
(def expensive-indexes
  [(create-index :gram-1 :pos-1)

   (create-index :gram-2 :pos-1)
   (create-index :gram-2 :pos-2)

   (create-index :gram-3 :pos-1)
   (create-index :gram-3 :pos-2)
   (create-index :gram-3 :pos-3)

   (create-index :gram-4 :pos-1)
   (create-index :gram-4 :pos-2)
   (create-index :gram-4 :pos-3)
   (create-index :gram-4 :pos-4)

   (create-index :tokens :sentences-id)
   (create-index :tokens :lemma)
   (create-index :tokens :orth-base)

   (create-index :unigrams :sentences-id)
   (create-index :unigrams :string)
   (create-index :unigrams :pos)
   (h/raw "ANALYZE")])

;; ### Collocation N-gram Search Tables
;;
;; Collocation n-gram search tables are divided into two schemas per n.
;; Tables beginning with search_sentences_ are for searching example sentences, while
(def resorted-gram-tables
  [;; 1-gram does not need sorting
   (create-index :gram-1 :sentences-id)
   (add-fk :gram-1 :sentences :id)
   (create-index :gram-1 :string-1)

   ;; 2-grams:
   (rename-table :gram-2 :gram-2-renamed)
   (create-unlogged-table-as :gram-2
                             (-> (select :*)
                                 (from :gram-2-renamed)
                                 (order-by :pos-1 :pos-2 :string-1 :string-2 :tags-1 :tags-2 :sentences-id :begin-1)))
   (h/raw "DROP TABLE gram_2_renamed CASCADE")
   (create-index :gram-2 :sentences-id)
   (add-fk :gram-2 :sentences :id)
   (create-index :gram-2 :pos-1)
   (create-index :gram-2 :pos-2)
   (create-index :gram-2 :string-1)
   (create-index :gram-2 :string-2)
   ;; TODO benchmark CLUSTER

   ;; 4-grams:

   (rename-table :gram-4 :gram-4-renamed)
   (create-unlogged-table-as :gram-4
                             (-> (select :*)
                                 (from :gram-4-renamed)
                                 (order-by :pos-1 :pos-2 :pos-3 :pos-4 :string-1 :string-2 :string-3 :string-4 :tags-1 :tags-2 :tags-3 :tags-4 :sentences-id :begin-1)))
   (h/raw "DROP TABLE gram_4_renamed CASCADE")
   (create-index :gram-4 :sentences-id)
   (add-fk :gram-4 :sentences :id)
   (create-index :gram-4 :pos-1)
   (create-index :gram-4 :pos-2)
   (create-index :gram-4 :pos-3)
   (create-index :gram-4 :pos-4)
   (create-index :gram-4 :string-1)
   (create-index :gram-4 :string-2)
   (create-index :gram-4 :string-3)
   (create-index :gram-4 :string-4)

   ;; Doc FIXME UNION with 1,2,3 of gram_4 -- but be careful when talking about absolute number of n-grams (emphasize we are talking about grams in 2 dependent chunks).
   ;; 3-grams:

   (rename-table :gram-3 :gram-3-renamed)

   (h/raw "CREATE UNLOGGED TABLE gram_3 AS

   WITH temporary_union AS (
   (SELECT * FROM gram_3_renamed)
   UNION
   (SELECT sentences_id, string_1, pos_1, tags_1, begin_1, end_1, string_2, pos_2, tags_2, begin_2, end_2, string_3, pos_3, tags_3, begin_3, end_3 FROM gram_4))

   SELECT * FROM temporary_union ORDER BY pos_1, pos_2, pos_3, string_1, string_2, string_3, tags_1, tags_2, tags_3, sentences_id, begin_1")

   (h/raw "DROP TABLE gram_3_renamed CASCADE")
   (create-index :gram-3 :sentences-id)
   (add-fk :gram-3 :sentences :id)
   (create-index :gram-3 :pos-1)
   (create-index :gram-3 :pos-2)
   (create-index :gram-3 :pos-3)
   (create-index :gram-3 :string-1)
   (create-index :gram-3 :string-2)
   (create-index :gram-3 :string-3)

   (h/raw "ANALYZE")])

(def norm-table
  ;; ### Normalization Tables
  ;;
  ;; The norm table contains aggregate counts per genre that are used for normalizing specific counts.
  ;; Currently, token counts, sentence counts, paragraph counts, and source counts are available.
  ;;
  ;; As paragraph id's are not unique across sources in sentences table, we need to make a temporary paragraph-by-source count table.
  ;;(let [g2-keys (set (with-dbmacro (j/with-query-results res [""])))])
  ;; FIXME: anything to be done with pivot tables....?

  [(h/raw
    "CREATE TABLE genre_norm AS
   SELECT so.genre,
          sum(se.tokens)::integer         AS token_count,
          sum(se.chunks)::integer         AS chunk_count,
          count(DISTINCT se.id)::integer  AS sentences_count,
          count(DISTINCT so.id)::integer  AS sources_count
   FROM sentences AS se,
        sources AS so
   WHERE se.sources_id=so.id
   GROUP BY so.genre")
   ;; No indexes for now as it is a small table.

   ;; TODO: gram counts, etc.
   ;; Collocation n-gram counts are recorded under a different schema as the number of collocation types is more dynamic.
   (h/raw "CREATE TABLE gram_norm AS
   (SELECT g1.pos_1 AS type,
           so.genre,
           count(*) AS count,
           count(DISTINCT se.id) AS sentences_count,
           -- FIXME
           count(DISTINCT so.id) AS sources_count
   FROM gram_1 AS g1,
        sentences AS se,
        sources AS so
   WHERE g1.sentences_id=se.id AND se.sources_id=so.id
   GROUP BY so.genre, g1.pos_1
   ORDER BY type, so.genre, count)

   UNION

   (SELECT (g2.pos_1 || '_' || g2.pos_2) AS type,
           so.genre,
           count(*) AS count,
           count(DISTINCT se.id) AS sentences_count,
           -- FIXME
           count(DISTINCT so.id) AS sources_count
   FROM gram_2 AS g2,
        sentences AS se,
        sources AS so
   WHERE g2.sentences_id=se.id AND se.sources_id=so.id
   GROUP BY so.genre, g2.pos_1, g2.pos_2
   ORDER BY type, so.genre, count)

   UNION

   (SELECT (g3.pos_1 || '_' || g3.pos_2 || '_' || g3.pos_3) AS type,
           so.genre,
           count(*) AS count,
           count(DISTINCT se.id) AS sentences_count,
           -- FIXME
           count(DISTINCT so.id) AS sources_count
   FROM gram_3 AS g3,
        sentences AS se,
        sources AS so
   WHERE g3.sentences_id=se.id AND se.sources_id=so.id
   GROUP BY so.genre, g3.pos_1, g3.pos_2, g3.pos_3
   ORDER BY type, so.genre, count)

   UNION

   (SELECT (g4.pos_1 || '_' || g4.pos_2 || '_' || g4.pos_3 || '_' || g4.pos_4) AS type,
           so.genre,
           count(*) AS count,
           count(DISTINCT se.id) AS sentences_count,
           -- FIXME
           count(DISTINCT so.id) AS sources_count
   FROM gram_4 AS g4,
        sentences AS se,
        sources AS so
   WHERE g4.sentences_id=se.id AND se.sources_id=so.id
   GROUP BY so.genre, g4.pos_1, g4.pos_2, g4.pos_3, g4.pos_4
   ORDER BY type, so.genre, count)")
   ;; We commonly dispatch on type, so the index here can be justified.
   ;; Genre must be profiled.
   (create-index :gram-norm :type)
   (h/raw "ANALYZE")])

(def search-table
  ;; TODO: find right place to filter rare collocations, especially on Wikipedia data (probably filter with HAVING clause).
  ;; TODO: should the token search table include computed measures like tf-idf?
  ;; TODO: cast counts as integer
  ;; FIXME 助動詞 (everything but 動詞?) should have c-form added to pos-2(1?) to differentiate between だ、な、に etc.
  [(create-table-as :search-tokens
                    {:select [:pos-1 :pos-2 :c-form :orth-base :lemma :genre
                              [(h/call :count :pos-1) :count]
                              [(h/call :count-distinct :sentences.id) :sentences-count]
                              [(h/call :count-distinct :sources.id) :sources-count]]
                     :from [:tokens :sentences :sources]
                     :where [:and
                             [:= :tokens.sentences-id :sentences.id]
                             [:= :sentences.sources-id :sources.id]]
                     :group-by [:pos-1 :pos-2 :c-form :orth-base :lemma :genre]
                     :order-by [:lemma :orth-base :pos-1 :pos-2 :c-form :genre :count]})
   (create-index :search-tokens :genre :gist)
   (create-index :search-tokens :genre)
   (create-index :search-tokens :pos-1)
   (create-index :search-tokens :pos-2)
   (create-index :search-tokens :orth-base)
   (create-index :search-tokens :lemma)

   ;; FIXME TODO consider adding array of sentence ids per row?
   ;; TODO need a clear search case for these (and for normal gram_X tables for that matter).
   (h/raw
    "CREATE TABLE search_gram_1 AS
    SELECT pos_1 AS type, g1.string_1, so.genre, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT so.id)::integer as sources_count FROM gram_1 AS g1, sentences AS se, sources AS so WHERE g1.sentences_id=se.id AND se.sources_id=so.id GROUP BY pos_1, g1.string_1, so.genre ORDER BY pos_1, g1.string_1, so.genre, count")
   (create-index :search-gram-1 :genre :gist)
   (create-index :search-gram-1 :genre)
   (create-index :search-gram-1 :type)
   (create-index :search-gram-1 :string-1)

   (h/raw
    "CREATE TABLE search_gram_2 AS
     WITH
       f_ix AS (SELECT (pos_1 || '_' || pos_2) AS t, string_1, genre, count(string_2)::integer FROM gram_2, sentences, sources WHERE gram_2.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_1, genre),
       f_xi AS (SELECT (pos_1 || '_' || pos_2) AS t, string_2, genre, count(string_1)::integer FROM gram_2, sentences, sources WHERE gram_2.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_2, genre)
    SELECT (pos_1 || '_' || pos_2) AS type, g2.string_1, g2.string_2, so.genre, f_ix.count AS f_ix, f_xi.count AS f_xi, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT so.id)::integer as sources_count FROM gram_2 AS g2, sentences AS se, sources AS so, f_ix, f_xi WHERE g2.sentences_id=se.id AND se.sources_id=so.id AND f_ix.t=(pos_1 || '_' || pos_2) AND f_xi.t=(pos_1 || '_' || pos_2) AND f_ix.string_1=g2.string_1 and f_xi.string_2=g2.string_2 AND f_ix.genre=so.genre AND f_xi.genre=so.genre GROUP BY pos_1, pos_2, g2.string_1, g2.string_2, so.genre, f_ix, f_xi ORDER BY (pos_1 || '_' || pos_2), g2.string_1, g2.string_2, so.genre, count")

   (create-index :search-gram-2 :genre :gist)
   (create-index :search-gram-2 :genre)
   (create-index :search-gram-2 :type)
   (create-index :search-gram-2 :string-1)
   (create-index :search-gram-2 :string-2)


   (h/raw
    "CREATE TABLE search_gram_3 AS
     WITH
       f_ix AS (SELECT (pos_1 || '_' || pos_2 || '_' || pos_3) AS t, string_1, genre, count(string_3)::integer FROM gram_3, sentences, sources WHERE gram_3.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_1, genre),
       f_xi AS (SELECT (pos_1 || '_' || pos_2 || '_' || pos_3) AS t, string_3, genre, count(string_1)::integer FROM gram_3, sentences, sources WHERE gram_3.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_3, genre)
    SELECT (pos_1 || '_' || pos_2 || '_' || pos_3) AS type, g3.string_1, g3.string_2, g3.string_3, so.genre, f_ix.count AS f_ix, f_xi.count AS f_xi, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT so.id)::integer as sources_count FROM gram_3 AS g3, sentences AS se, sources AS so, f_ix, f_xi WHERE g3.sentences_id=se.id AND se.sources_id=so.id AND f_ix.t=(pos_1 || '_' || pos_2 || '_' || pos_3) AND f_xi.t=(pos_1 || '_' || pos_2 || '_' || pos_3) AND f_ix.string_1=g3.string_1 and f_xi.string_3=g3.string_3 AND f_ix.genre=so.genre AND f_xi.genre=so.genre GROUP BY pos_1, pos_2, pos_3, g3.string_1, g3.string_2, g3.string_3, so.genre, f_ix, f_xi ORDER BY (pos_1 || '_' || pos_2 || '_' || pos_3), g3.string_1, g3.string_2, g3.string_3, so.genre, count")

   (create-index :search-gram-3 :genre :gist)
   (create-index :search-gram-3 :genre)
   (create-index :search-gram-3 :type)
   (create-index :search-gram-3 :string-1)
   (create-index :search-gram-3 :string-2)
   (create-index :search-gram-3 :string-3)

   ;; Note: f-io and f-oi are fixed to string-1 and string-3 in 4-grams too (FIXME).
   (h/raw
    "CREATE TABLE search_gram_4 AS
     WITH
       f_ix AS (SELECT (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AS t, string_1, genre, count(string_3)::integer FROM gram_4, sentences, sources WHERE gram_4.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_1, genre),
       f_xi AS (SELECT (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AS t, string_3, genre, count(string_1)::integer FROM gram_4, sentences, sources WHERE gram_4.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_3, genre)
    SELECT (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AS type, g4.string_1, g4.string_2, g4.string_3, g4.string_4, so.genre, f_ix.count AS f_ix, f_xi.count AS f_xi, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT so.id)::integer as sources_count FROM gram_4 AS g4, sentences AS se, sources AS so, f_ix, f_xi WHERE g4.sentences_id=se.id AND se.sources_id=so.id AND f_ix.t=(pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AND f_xi.t=(pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AND f_ix.string_1=g4.string_1 and f_xi.string_3=g4.string_3 AND f_ix.genre=so.genre AND f_xi.genre=so.genre GROUP BY pos_1, pos_2, pos_3, pos_4, g4.string_1, g4.string_2, g4.string_3, g4.string_4, so.genre, f_ix, f_xi ORDER BY (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4), g4.string_1, g4.string_2, g4.string_3, g4.string_4, so.genre, count")

   (create-index :search-gram-4 :genre :gist)
   (create-index :search-gram-4 :genre)
   (create-index :search-gram-4 :type)
   (create-index :search-gram-4 :string-1)
   (create-index :search-gram-4 :string-2)
   (create-index :search-gram-4 :string-3)
   (create-index :search-gram-4 :string-4)
   (h/raw "ANALYZE")])

(defn create-search-tables! [conn & {:keys [resort?] :or {resort? true}}]
  (if resort? ;; FIXME one big transaction might be overkill?
    (seq-execute! conn expensive-indexes resorted-gram-tables norm-table search-table)
    (seq-execute! conn norm-table search-table)))

(defn drop-search-tables! [conn]
  (doseq [t [(ddl/drop-table :genre_norm)
             (ddl/drop-table :gram_norm)
             (ddl/drop-table :search_tokens)
             (ddl/drop-table :search_gram_1)
             (ddl/drop-table :search_gram_2)
             (ddl/drop-table :search_gram_3)
             (ddl/drop-table :search_gram_4)]]
    (try (e! conn [t])
         (catch Exception e))))


;; END Schema

;; Query


;; ## Insertion functions

;; ### Sources

(defn insert-source! [conn sources-metadata]
  (i! conn :sources (update-in sources-metadata [:genre] seq->ltree)))

(defn insert-sources!
  "Inserts sources meta-information from the corpus into the database.

  If a file is not present in `sources.tsv`, bail with a fatal error (FIXME)
  message; if a file is not in `sources.tsv` or not on the filesystem,
  then ignore that file (do not insert.)"
  [conn sources-metadata file-set]
  (->> sources-metadata
       (filter (fn [{:keys [basename]}] (contains? file-set basename)))
       ;; Optionally filter out sources already in database.
       ;;(?>> (not-empty existing-basenames) (map #(filter (fn [record] (not (contains? existing-basenames (nth record 3)))) %)))
       ((comp dorun map) (partial insert-source! conn))
       #_((comp dorun map) #(if (seq %) ((comp dorun map) insert-source! %)))))

;; ### Sentence

(defn insert-sentence [conn sentence-values]
  (i! conn
      :sentences
      (-> sentence-values
          #_(update-in [:s] make-jdbc-array)
          (select-keys (schema-keys sentences-schema)))))

;; ### Collocations
(defn insert-collocations! [conn collocations sentences-id]
  (doseq [collocation collocations]
    (let [grams (count (:type collocation))
          record-map (apply merge
                            (for [i (range 1 (inc grams))]
                              (let [record (nth (:data collocation) (dec i))]
                                (map-keys #(let [[f s] (string/split (name %) #"-")]
                                            (keyword (str s "-" i)))
                                          (-> record
                                              (?> (:head-pos record)  (update-in [:head-pos] name))
                                              (?> (:tail-pos record)  (update-in [:tail-pos] name))
                                              (?> (:head-tags record) (update-in [:head-tags] (fn->> (map name) (into #{}))))
                                              (?> (:tail-tags record) (update-in [:tail-tags] (fn->> (map name) (into #{})))))))))]
      (i! conn
          (keyword (str "gram-" grams))
          (assoc record-map :sentences-id sentences-id)))))

;; ### Unigrams
(defn insert-unigrams! [conn unigrams sentences-id]
  (i! conn
      :unigrams
      (map-indexed
       (fn [i token]
         (assoc token :position i :sentences-id sentences-id))
       unigrams)))

;; ### Tokens
(defn insert-tokens! [conn token-seq sentences-id]
  (i! conn
      :tokens
      (map-indexed
       (fn [i token]
         (assoc
          (select-keys token [:pos :pos-1 :pos-2 :pos-3 :pos-4 :c-type :c-form :lemma :orth :pron :orth-base :pron-base :goshu :tags])
          :position i
          :sentences-id sentences-id))
       token-seq)))

;; ## Query functions

(defn basename->source-id
  [conn basename]
  (-> (q conn
         (-> (select :id)
             (from :sources)
             (where [:= :basename basename])))
      first
      :id))

(defn get-genres [conn]
  (distinct (map :genre
                 (q conn
                    (-> (select :genre)
                        (from :sources)
                        (order-by :genre))))))

(defn get-genre-counts [conn]
  (q conn
     {:select [:genre [(h/call :count :*) :count]]
      :from [:sources]
      :group-by [:genre]}))

(defn genres->tree [conn]
  (seq-to-tree (get-genre-counts conn)))

(defn sources-id->genres-map [conn sources-id]
  (->> (q conn
          {:select [:genre]
           :from [:sources]
           :where [:= :id sources-id]})
       (map :genre)
       distinct))

(defn sentences-by-genre [conn genre]
  (let [query (if (< (count genre) 4) (conj genre "*") genre)]
    (println query)
    (map :text
         (q conn
            (-> (select :text)
                (from :sentences :sources)
                (where [:and
                        [:= :sentences.sources_id :sources.id]
                        [:tilda :sources.genre query]]))))))

(defn tokens-by-genre [conn genre & {:keys [field] :or {field :lemma}}]
  (let [query (if (< (count genre) 4) (conj genre "*") genre)]
    (println query)
    (mapcat vals
            (q conn #_(h/raw (str "SELECT string_agg(tokens.orth, ' ') FROM tokens, sentences, sources WHERE tokens.sentences_id=sentences.id AND sentences.sources_id=sources.id AND sources.genre ~ '" query "' GROUP BY tokens.sentences_id"))
               (-> (select (h/raw (str "string_agg(tokens." (name field) ", ' ')")))
                   (from :tokens :sentences :sources)
                   (where [:and
                           [:= :tokens.sentences-id :sentences.id]
                           [:= :sentences.sources-id :sources.id]
                           [:tilda :sources.genre genre]])
                   (group :tokens.sentences-id))))))


(defn all-sentences-with-genre [conn]
  (q conn
     (-> (select :text :sources.genre)
         (from :sentences :sources)
         (where [:= :sentences.sources_id :sources.id])
         (group :sources.genre :sentences.id))))

(defn sources-ids-by-genre [conn genre]
  (let [query (if (< (count genre) 4) (conj genre "*") genre)]
    (map :id
         (q conn
            (-> (select :id)
                (from :sources)
                (where [:tilda :genre query]))))))

(defn sources-text [conn id]
  (map :text
       (q conn
          (-> (select :text)
              (from :sentences :sources)
              (where [:and
                      [:= :sources.id id]
                      [:= :sentences.sources_id :sources.id]])))))

(defn sources-tokens [conn id & {:keys [field] :or {field :lemma}}]
  (mapcat vals
          (q conn
             (-> (select (h/raw (str "string_agg(tokens." (name field) ", ' ')")))
                 (from :tokens :sentences :sources)
                 (where [:and
                         [:= :sources.id id]
                         [:= :tokens.sentences-id :sentences.id]
                         [:= :sentences.sources_id :sources.id]])
                 (group :tokens.sentences-id)))))

(def !norm-map (atom {}))
(def !genre-names (atom #{}))
(def !genre-tokens-map (atom {}))
#_(def !pos-genre-tokens (atom {}))
(defn set-norm-map! [conn]
  (reset! !norm-map
   {:sources    (seq-to-tree (q conn (-> (select :genre [:sources-count :count]) (from :genre-norm)) genre-ltree-transform))
    :sentences  (seq-to-tree (q conn (-> (select :genre [:sentences-count :count]) (from :genre-norm)) genre-ltree-transform))
    :chunks     (seq-to-tree (q conn (-> (select :genre [:chunk-count :count]) (from :genre-norm)) genre-ltree-transform))
    :tokens     (seq-to-tree (q conn (-> (select :genre [:token-count :count]) (from :genre-norm)) genre-ltree-transform))})
  (reset! !genre-names (->> @!norm-map :sources :children (map :name) set))
  (reset! !genre-tokens-map (->> @!norm-map :tokens :children (map (juxt :name :count)) (into {})))
  #_(let [poss (->> (q conn (-> (select (h/call :distinct :pos-1)) (from :search-tokens))) (map :pos-1) (into #{}))] ;; FIXME: :pos-1/2/3 vs :pos
    (reset! !pos-genre-tokens
            (for-map [pos poss]
              pos
              (seq-to-tree
                (q conn (-> (select :genre [(h/call :sum :count) :count]) (from :search-tokens) (where [:= :pos-1 "副詞"]) (group :genre))
                   genre-ltree-transform))))))

;; FIXME TODO add compact-numbers
;; TODO add natsume-units version
(defn get-search-tokens [conn query-map & {:keys [norm] :or {norm :tokens}}]
  (->> (qm conn
           {:select [:*]
            :from [:search-tokens]
            :where (map->and-query (select-keys query-map [:lemma :orth-base :pos-1 :pos-2]))}
          genre-ltree-transform)
       (group-by #(select-keys % [:lemma :orth-base :pos-1 :pos-2]))
       (map-vals seq-to-tree)
       ;; Optionally normalize results if :norm key is set and available.
       (?>> (contains? @!norm-map norm) (map-vals #(normalize-tree (norm @!norm-map) % {:clean-up-fn compact-number})))
       ;; Below is for API/JSON TODO (might want to move below to service.clj) as it is more JSON/d3-specific
       vec
       (map #(hash-map :token (first %) :results (second %)))))
(comment
  (not= (get-search-tokens {:orth-base "こと"} :norm :sentences)
        (get-search-tokens {:orth-base "こと"})))

(defn get-one-search-token [conn query-map & {:keys [norm compact-numbers] :or {norm :tokens compact-numbers true}}]
  (->> (qm conn
           {:select [:*]
            :from [:search-tokens]
            :where (map->and-query (select-keys query-map [:lemma :orth-base :pos-1 :pos-2]))}
           genre-ltree-transform)
       seq-to-tree
       ;; Optionally normalize results if :norm key is set and available.
       (?>> (and norm (contains? @!norm-map norm)) (#(normalize-tree (norm @!norm-map) % {:clean-up-fn (if compact-numbers compact-number identity)})))
       ;; Below is for API/JSON TODO (might want to move below to service.clj) as it is more JSON/d3-specific
       ))

;; Should contain all totals in a map by collocation type (n-gram size is determined by type) and genre.
(def !gram-totals (atom {}))
(def !gram-types (atom #{}))
(def !tokens-by-gram (atom {}))

(defn set-gram-information! [conn]
  (reset! !gram-totals
         (let [records (q conn (-> (select :*) (from :gram-norm)) #(update-in % [:type] underscores->dashes))]
           (->> records
                (map #(update-in % [:genre] ltree->seq))
                (group-by :type)
                (map-vals #(seq-to-tree % {:merge-fns {:count +
                                                       :sentences-count +
                                                       :sources-count +}})))))
  (reset! !gram-types (set (q conn (-> (select :type) (from :gram-norm)) underscores->dashes :type)))
  (reset! !tokens-by-gram
         (map-vals (fn [x] (reduce #(+ %1 (-> %2 val :count)) 0 x))
                   (group-by #(let [wcs (clojure.string/split (name (key %)) #"-")
                                    aux-count (count (filter (fn [wc] (= "auxiliary" wc)) wcs))]
                               (- (count wcs) aux-count))
                             @!gram-totals))))

;; TODO custom-query to supplement and-query type queries (i.e. text match LIKE "%考え*%")
;; TODO break function into two for streamlined API: general collocation query and tree-seq query
;; FIXME shape of data returned should be the same for all types of queries.
;; FIXME normalization is needed for seq-tree -- another reason to split function. The implementation should address the issue of normalizing before measure calculations, otherwise they will not be done correctly???? Is the contingency table per genre????
(defn query-collocations
  "Query n-gram tables with the following optional parameters:
  -   type: collocation type (default :noun-particle-verb)
  -   aggregates: fields to sum over (default: [:count :f-io :f-oi])
  -   selected: fields to return (default: [:string-1 :string-2 :string-3 :type :genre])
  -   genre: optionally filter by genre (default: all data)"
  [conn
   {:keys [string-1 string-2 string-3 string-4 type genre offset limit measure compact-numbers scale relation-limit]
    :or {type :noun-particle-verb
         measure :count
         compact-numbers true
         scale false}
    :as m}]
  (let [n (count (string/split (name type) #"-"))
        aggregates (if (= n 1) [:count] [:count :f-ix :f-xi])
        query (select-keys m [:string-1 :string-2 :string-3 :string-4])
        measure (keyword measure)
        select-fields (->> [:string-1 :string-2 :string-3 :string-4] (take n) (into #{}))
        selected (set/difference select-fields
                                 (set aggregates)
                                 (set (keys query)))
        aggregates-clause (mapv (fn [a] [(h/call :sum a) a]) aggregates)
        query-clause (map->and-query query)
        where-clause (-> query-clause
                         (conj [:= :type (fmt/to-sql type)])
                         (?> genre (conj [:tilda :genre genre])))
        clean-up-fn (fn [data] (->> data
                                    (?>> (and offset (pos? offset)) (drop offset))
                                    (?>> (and limit (pos? limit)) (take limit))
                                    (?>> compact-numbers (map (fn [r] (update-in r [measure] compact-number))))))]
    (->> (qm conn
             (-> {:select (vec (distinct (concat aggregates-clause selected)))
                  :from [(keyword (str "search_gram_" n))]
                  :where where-clause}
                 (?> (not-empty selected) (assoc :group-by selected))))
         #_(map genre-ltree-transform)
         (?>> (> n 1) (map #(let [contingency-table (stats/expand-contingency-table
                                                      {:f-ii (:count %) :f-ix (:f-ix %) :f-xi (:f-xi %)
                                                       ;; FIXME : we probably want to have the option of using the total count per n-gram order...
                                                       :f-xx (-> @!gram-totals type :count)})] ;; FIXME Should add :genre filtering to !gram-totals when we specify some genre filter!
                             (case measure
                               :log-dice (merge % contingency-table)
                               :count % ;; FIXME count should be divided by :f-xx (see above), especially when filtering by genre.
                               (assoc % measure
                                        ((measure stats/association-measures-graph) contingency-table))))))
         (?>> (= :log-dice measure) ((fn [coll] (stats/log-dice coll (if (:string-1 query) :string-3 :string-1)))))
         (map #(-> % (dissoc :f-ii :f-io :f-oi :f-oo :f-xx :f-ix :f-xi :f-xo :f-ox) (?> (not= :count measure) (dissoc :count))))
         (sort-by (comp - measure)) ;; FIXME group-by for offset+limit after here, need to modularize this following part to be able to apply on groups
         (?>> (:string-2 selected) ((fn [rows] (->> rows
                                                    (group-by :string-2)
                                                    (map-vals (fn [row] (map #(dissoc % :string-2) row)))
                                                    ;; Sorting assumes higher measure values are better.
                                                    (sort-by #(- (apply + (map measure (second %)))))
                                                    (map (fn [[p fs]] {:string-2 p
                                                                       :data (clean-up-fn fs)}))
                                                    (?>> relation-limit (take relation-limit))))))
         (?>> (not (:string-2 selected)) (clean-up-fn)))))
;; FIXME include option for human-readable output (log-normalized to max): scale option

(comment
  (use 'clojure.pprint)
  (pprint (query-collocations conn {:string-1 "こと" :string-2 "が" :measure :t})))


;; FIXME / TODO: optimization: !! do not select :f-ix/:f-xi when measure is :count. !!
(defn query-collocations-tree
  [conn
   {:keys [string-1 string-2 string-3 string-4 type genre tags measure compact-numbers normalize?]
    :or {type :noun-particle-verb
         measure #{:count} ;; (keys stats/association-measures-graph)
         normalize? true
         compact-numbers true}
    :as m}]
  (let [n (count (string/split (name type) #"-"))
        aggregates (if (= n 1) [:count] [:count :f-ix :f-xi])
        query (select-keys m [:string-1 :string-2 :string-3 :string-4])
        measure (if (set? measure) measure (into #{} measure))
        select-fields (->> [:string-1 :string-2 :string-3 :string-4] (take n) (into #{}))
        selected (if-let [s (seq (set/difference select-fields
                                                 (set aggregates)
                                                 (set (keys query))))]
                   (set s)
                   ;; When query is fully specified, we rather return a genre tree of results.
                   #{:genre})
        aggregates-clause (mapv (fn [a] [(h/call :sum a) a]) aggregates)
        query-clause (map->and-query query)
        where-clause (-> query-clause
                         (conj [:= :type (fmt/to-sql type)])
                         (?> genre (conj [:tilda :genre genre])))
        merge-fns (for-map [m measure] m (if (#{:count :f-xi :f-ix #_:f-io #_:f-oi} m) + #(if %1 %1 %2)))
        clean-up-fn (fn [data] (->> data
                                   (?>> compact-numbers (map (fn [r] (for-map [[k v] r] k (if (measure k) (compact-number v) v)))))))]
    (if (= (count query) n) ;; Only fully-specified queries are allowed.
      (if (= n 1) ;; 1-gram is a specialized case, as it uses :tags. -> not anymore.
        (let [tags (if (empty? tags) #{:none} tags)
              db-results (qm conn
                             {:select (vec (distinct (concat aggregates-clause selected #_[:tags])))
                              :from [(keyword (str "search_gram_" n))]
                              :where where-clause
                              :group-by selected #_(conj selected :tags)}
                             genre-ltree-transform)
              tree (seq-to-tree db-results)]
          (if (empty? db-results)
            tree
            (if normalize?
              (normalize-tree (get @!gram-totals type) tree
                              {:clean-up-fn (if compact-numbers compact-number identity)})
              tree)))
        ;; n > 1
        (let [merge-stats
              (fn [record]
                (let [contingency-table
                      (stats/expand-contingency-table
                       {:f-ii (:count record)
                        :f-ix (:f-ix record)
                        :f-xi (:f-xi record)
                        :f-xx (get @!tokens-by-gram n) #_(-> @!gram-totals type :count)})]
                  (-> record
                      (merge contingency-table)
                      (merge (for-map [m (set/difference measure #{:count :f-ix :f-xi :log-dice})]
                                 m ((m stats/association-measures-graph) contingency-table))))))

              db-results
              (qm conn
                  {:select (vec (distinct (concat aggregates-clause selected)))
                   :from [(keyword (str "search_gram_" n))]
                   :where where-clause
                   :group-by selected}
                  genre-ltree-transform
                  ;; FIXME tree contingency table is broken right now?? need to first sum up the counts for correct genre comparisons??? or is the f-xx here good enough (it's not, because it ignores genre counts).
                  merge-stats)]
          (if (empty? db-results)
            (seq-to-tree db-results)
            (-> db-results
                ;;(?> (:log-dice measure) (stats/log-dice (if (:string-1 query) :string-3 :string-1)))
                ;;((fn [rs] (map #(-> % (dissoc :f-ii :f-io :f-oi :f-oo :f-xx :f-ix :f-xi :f-xo :f-ox) (?> (not= (:count measure)) (dissoc :count))) rs)))
                (?> (and (not (:count measure)) compact-numbers)
                    ((fn [rs]
                       (map (fn [r]
                              (for-map [[k v] r] k (if (measure k) (compact-number v) v)))
                            rs))))
                (seq-to-tree {:merge-fns merge-fns :root-values (select-keys (merge-stats (into {} (r/reduce (fn [a kvs] (merge-with merge-fns a kvs)) {} (map #(dissoc % :genre) db-results)))) measure)})
                (?> (and (:count measure) normalize?) ((fn [tree] (normalize-tree (get @!gram-totals type) tree {:clean-up-fn (if compact-numbers compact-number identity)})))))))))))

(comment

  (pprint (query-collocations-tree {:string-1 "こと" :string-2 "が" :string-3 "できる" :measure :t})))

;; TODO look for similarities in query-collocations and below: the selection seems a bit better here?? for tokens, we know the name of the fields ahead of time, while with grams, we have to choose per n
;; TODO graph?
(defn query-tokens
  [{:keys [query selected aggregates]
    :or {selected [:lemma :orth-base :pos-1 :pos-2 :genre]
         aggregates [:count]}}]
  {:select (set/difference (set (concat selected aggregates))
                           (set (keys query)))
   :from [:search-tokens]
   :where (map->and-query query)})

;; FIXME: this is actually not useful as we want to sort by measures....
;; :order-by (reduce #(conj %1 [%2 :desc]) [] aggregates)

(defn tag-html
  "Inserts html span tags into text given starting and ending indexes."
  [{:keys [text] :as m}]
  (let [tags (->> (select-keys m [:begin-1 :begin-2 :begin-3 :begin-4 :end-1 :end-2 :end-3 :end-4])
                  (group-by (fn [t] (-> t first name (string/split #"-") second (#(Long/parseLong %)))))
                  (sort-by key >))]
    (-> m
        (assoc :text
          (reduce
           (fn [tagged-text [n begin-end]]
             (let [[begin-index end-index] (-> (into {} begin-end)
                                               (map [(keyword (str "begin-" n))
                                                     (keyword (str "end-" n))]))
                   key-string (str "<span class=\"key" n "\">" (subs tagged-text begin-index end-index) "</span>")
                   before-string (subs tagged-text 0 begin-index)
                   after-string (subs tagged-text end-index (count tagged-text))]
               (str before-string key-string after-string)))
           text
           tags))
        (dissoc :begin-1 :begin-2 :begin-3 :begin-4 :end-1 :end-2 :end-3 :end-4))))

(defn query-sentences
  "Query sentences containing given collocations, up to 'limit' times per top-level genre.
  Including the optional genre parameter will only return sentences from given genre, which can be any valid PostgreSQL ltree query."
  [conn
   {:keys [type limit offset genre html sort]
    :or {limit 6 offset 0 type :noun-particle-verb}
    :as m}]
  (let [sort (if sort
               (if (re-seq #"(?i)^(length|tokens|chunks|jlpt.?level|bccwj.?level|link.?dist|chunk.?depth)$" sort) (str (name (underscores->dashes sort)) " ASC") "abs(4 - chunks)")
               "abs(4 - chunks)")
        type-vec (-> type name (string/split #"-"))
        type-fields (into {} (map-indexed (fn [i t] [(keyword (str "pos-" (inc i))) t]) type-vec))
        query-fields (merge (select-keys m [:string-1 :string-2 :string-3 :string-4]) type-fields)
        selected-fields [:sources.genre :sources.title :sources.author :sources.year :sentences.text]
        n (count type-vec)
        search-table (keyword (str "gram_" n))
        begin-end-fields (for [number (range 1 (inc n))
                               s ["begin-" "end-"]]
                           (keyword (str s number)))]
    (q conn {:select [(h/call :setseed 0.2)]}) ; FIXME Better way to get consistent ordering? -> when setting connection?
    (qm conn
        {:select [:*]
         :where  [:<= :part.r limit]
         :from   [[{:select   (-> selected-fields
                                  (concat begin-end-fields)
                                  (conj [(h/raw (str "ROW_NUMBER() OVER (PARTITION BY subltree(sources.genre, 0, 1) ORDER BY RANDOM(), " sort ")")) :r]))
                    :from     [search-table :sentences :sources]
                    :where    (-> (conj (map->and-query query-fields)
                                        [:= :sentences-id :sentences.id]
                                        [:= :sentences.sources-id :sources.id])
                                  (?> genre (conj [:tilda :sources.genre genre])))
                    :group-by (apply conj selected-fields :sentences.chunks begin-end-fields)}
                   :part]]}
        genre-ltree-transform
        #(dissoc % :r)
        (if html tag-html identity))))

(comment
  (query-sentences conn {:string-1 "こと" :type :noun-particle-verb})
  (query-sentences conn {:string-1 "こと" :type :noun-particle-verb-particle :genre ["書籍" "*"] :limit 10 :html true}))

(defn query-sentences-tokens
  "Query sentences containing given tokens, up to 'limit' times per top-level genre.
  Including the optional genre parameter will only return sentences from given genre, which can be any valid PostgreSQL ltree query."
  [conn
   {:keys [limit offset genre html sort] ;; FIXME lemma should be optional--instead we should require some set of orth/lemma/pron etc.
    :or {limit 6 offset 0}
    :as m}]
  (let [sort (if sort
               (if (re-seq #"(?i)^(length|tokens|chunks|jlpt.?level|bccwj.?level|link.?dist|chunk.?depth)$" sort) (str (name (underscores->dashes sort)) " ASC") "abs(4 - chunks)")
               "abs(4 - chunks)")
        query-fields (select-keys m [:orth :orth-base :lemma :pron :pron-base :pos-1 :pos-2 :pos-3 :pos-4 :c-form :c-type :goshu])
        selected-fields [:sources.genre :sources.title :sources.author :sources.year :sentences.text]
        search-table :tokens]
    (println query-fields)
    (q conn {:select [(h/call :setseed 0.2)]}) ; FIXME Better way to get consistent ordering? -> when setting connection?
    (qm conn
        {:select [:*]
         :where  [:<= :part.r limit]
         :from   [[{:select   (-> selected-fields
                                  (conj [(h/raw (str "ROW_NUMBER() OVER (PARTITION BY subltree(sources.genre, 0, 1) ORDER BY RANDOM(), " sort ")")) :r]))
                    :from     [search-table :sentences :sources]
                    :where    (-> (conj (map->and-query query-fields)
                                        [:= :sentences-id :sentences.id]
                                        [:= :sentences.sources-id :sources.id])
                                  (?> genre (conj [:tilda :sources.genre genre])))
                    :group-by (conj selected-fields :sentences.chunks)}
                   :part]]}
        genre-ltree-transform
        #(dissoc % :r)
        (if html tag-html identity))))

;; END Query


;; ## Computation graphs / pipeline pattern
(def sentence-graph
  {:tree            (fnk get-tree :- [Chunk] [text :- s/Str] (am/sentence->tree text))
   :features        rd/sentence-readability
   ;; The following are side-effecting persistence graphs:
   :sentences-id    (fnk get-sentences-id :- s/Num
                      [conn features tags paragraph-order-id sentence-order-id sources-id]
                      (-> (insert-sentence conn
                                           (assoc features
                                             :tags (into #{} (map name tags))
                                             :paragraph-order-id paragraph-order-id
                                             :sentence-order-id sentence-order-id
                                             :sources-id sources-id))
                          first
                          :id))
   :collocations-id (fnk get-collocations-id :- (s/maybe [s/Num]) [conn features sentences-id]
                      (when-let [collocations (seq (:collocations features))]
                        (map :id (insert-collocations! conn collocations sentences-id))))
   :commit-tokens   (fnk commit-tokens :- nil [conn tree sentences-id]
                         (insert-tokens! conn (flatten (map :tokens tree)) sentences-id))
   :commit-unigrams (fnk commit-unigrams :- nil [conn features sentences-id]
                         (insert-unigrams! conn (:unigrams features) sentences-id))})
(def sentence-graph-fn (graph/eager-compile sentence-graph))

(defnk insert-paragraphs! [conn paragraphs sources-id]
  (loop [paragraphs*       paragraphs
         sentence-start-id 1         ; ids start with 1 (same as SQL pkeys)
         paragraph-id      1]
    (when-let [paragraph (first paragraphs*)]
      (let [{:keys [sentences tags]} paragraph
            sentence-count (count sentences)
            sentence-end-id (+ sentence-start-id sentence-count)]
        ((comp dorun map)
         (s/fn [text :- s/Str sentence-order-id :- s/Num]
           (sentence-graph-fn
            {:conn               conn
             :tags               tags
             :sources-id         sources-id
             :sentence-order-id  sentence-order-id
             :paragraph-order-id paragraph-id
             :text               text}))
          sentences
          (range sentence-start-id sentence-end-id))
        (recur (next paragraphs*)
               sentence-end-id
               (inc paragraph-id))))))

(def file-graph
  {:paragraphs (fnk [filename] (-> filename str #_iota/vec iota/seq text/lines->paragraph-sentences text/add-tags))
   :sources-id (fnk [conn filename] (basename->source-id conn (fs/base-name filename true)))
   :persist    insert-paragraphs!})
(def file-graph-fn (graph/eager-compile file-graph))

(def bccwj-file-graph
  (assoc file-graph
    :paragraphs (fnk [filename]
                     (case (fs/extension filename)
                       ".txt" (-> filename str iota/vec text/lines->paragraph-sentences text/add-tags)
                       ".xml" (bccwj/xml->paragraph-sentences filename)))))
(def bccwj-file-graph-fn (graph/eager-compile bccwj-file-graph))

(def wikipedia-file-graph-fn (graph/eager-compile (dissoc file-graph :paragraphs)))

;; ## Graph and database connective logic
(defn sample [{:keys [ratio seed replace]} data]
  (let [total (count data)]
    (take (int (* ratio total)) (sampling/sample data :seed seed :replace replace))))

(defn dorunconc
  [f coll]
  ((comp dorun (partial pmap f)) coll))

(def corpus-graph
  ;; :files and :persist should be overridden for Wikipedia and BCCWJ.
  {:files      (fnk [corpus-dir sampling-options]
                 (->> corpus-dir
                      file-seq
                      (r/filter #(= ".txt" (fs/extension %)))
                      (into #{})
                      (?>> (not= (:ratio sampling-options) 0.0) ((fn [xs] (sample sampling-options xs))))))
   :file-bases (fnk [files] (set (map #(fs/base-name % true) files)))
   :sources    (fnk [corpus-dir]
                 (map
                   (fn [[title author year basename genres-name subgenres-name
                         subsubgenres-name subsubsubgenres-name permission]]
                     {:title    title
                      :author   author
                      :year     (Integer/parseInt year)
                      :basename basename
                      :genre    [genres-name subgenres-name subsubgenres-name subsubsubgenres-name]})
                   (with-open [sources-reader (io/reader (str corpus-dir "/sources.tsv"))]
                     (doall (csv/read-csv sources-reader :separator \tab :quote 0)))))
   :persist    (fnk [conn sources files file-bases]
                    ;; For non-BCCWJ and Wikipedia sources, we might want to run some sanity checks first.
                    (let [sources-basenames (set (map :basename sources))
                          basenames-missing-source (set/difference file-bases sources-basenames)]
                      (println "basenames missing from sources.tsv: (Warning: will be skipped!)")
                      (println basenames-missing-source)
                      (println "basenames in sources.tsv missing on filesystem: " (set/difference sources-basenames file-bases))
                      (insert-sources! conn sources (set/difference file-bases basenames-missing-source))
                      (->> files
                           (remove (fn [f] (contains? basenames-missing-source (fs/base-name f true))))
                           (dorunconc #(file-graph-fn {:conn conn :filename %})))))})

(def wikipedia-graph
  (merge (dissoc corpus-graph :file-bases :sources)
         {:files   (fnk [corpus-dir sampling-options]
                        (->> corpus-dir
                             file-seq
                             (filter #(= ".xml" (fs/extension %)))
                             (mapcat wikipedia/doc-seq) ; Should work for split and unsplit Wikipedia dumps.
                             (?>> (not= (:ratio sampling-options) 0.0) (take (int (* (:ratio sampling-options) 890089)))))) ; number is for Wikipedia as of 2013/12/03.
          :persist (fnk [conn files]
                        (->> files
                             (dorunconc (fn [file]
                                          (let [{:keys [sources paragraphs]} file]
                                            (insert-source! conn sources)
                                            (wikipedia-file-graph-fn {:conn conn
                                                                      :filename (:basename sources)
                                                                      :paragraphs paragraphs}))))))}))

(def bccwj-graph
  (merge corpus-graph
         {:files   (fnk [corpus-dir sampling-options]
                     (->> corpus-dir
                          file-seq
                          (filter #(= ".xml" (fs/extension %)))
                          (?>> (not= (:ratio sampling-options) 0.0) ((fn [xs] (sample sampling-options xs))))))
          :persist (fnk [conn sources files file-bases]
                     (insert-sources! conn sources file-bases)
                     (->> files
                          (dorunconc #(bccwj-file-graph-fn {:conn conn :filename %}))))}))

(s/defn process-corpus! :- nil
  [conn :- s/Any
   sampling :- {:ratio s/Num :seed s/Num :hold-out s/Bool :replace s/Bool}
   corpus-dir :- File]
  (let [corpus-computation (graph/eager-compile
                            (condp re-seq (.getPath corpus-dir)
                              #"(?i)wiki" wikipedia-graph
                              #"(?i)(LB|OB|OC|OL|OM|OP|OT|OV|OW|OY|PB|PM|PN)" bccwj-graph
                              corpus-graph))]
    (corpus-computation {:conn conn
                         :corpus-dir corpus-dir
                         :sampling-options sampling #_(env :sampling)})))

(s/defn process-directories :- #{File}
  "Processes directories to check if they exist and returns a set of io/file directory objects with canonical and normalized paths."
  [dirs :- [s/Str]]
  (if (seq dirs)
    (->> dirs
         (r/map io/file)
         (r/map fs/normalized)
         (r/filter fs/directory?)
         (into #{}))))

(s/defn process :- nil
  "Initializes database and processes corpus directories from input.
  If no corpus directory is given or the -h flag is present, prints
  out available options and arguments."
  [conn :- s/Any
   dirs :- [s/Str]
   sampling :- {:ratio s/Num :seed s/Num :hold-out s/Bool :replace s/Bool}]
  ((comp dorun map) (partial process-corpus! conn sampling) (process-directories dirs)))

;; Component

(defrecord Database [db-spec dirs sampling clean? search? process?]
  component/Lifecycle
  (start [this]

    ;; ## PostgreSQL setup.
    ;;
    ;; The following statements must be run on the PostgreSQL server:
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

    (let [conn (druid-pool db-spec)]
      (when clean?
        (drop-all-cascade! conn)
        (create-tables-and-indexes! conn))

      (when process?
        (process conn dirs sampling))

      (when search?
        (create-search-tables! conn))

      (set-norm-map! conn)
      (set-gram-information! conn)

      (assoc this :connection conn)))

  (stop [this] (dissoc this :connection)))

(defn database [{:keys [db dirs sampling clean search process] :as m}]
  (->Database db dirs sampling clean search process))
