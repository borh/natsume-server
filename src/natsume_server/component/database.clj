(ns natsume-server.component.database
  (:require [clojure.string :as string]
            [clojure.java.jdbc :as j]
            [java-jdbc.ddl :as ddl]
            [honeysql.core :as h]
            [honeysql.helpers :refer :all :exclude [update]]

            [d3-compat-tree.tree :refer [seq-to-tree normalize-tree]]

            [natsume-server.models.pg-types :refer :all]
            [natsume-server.utils.naming :refer [dashes->underscores underscores->dashes]]
            [natsume-server.models.dsl :refer :all]

            ;;[natsume-server.component.load :as load]

            ;;[plumbing.core :refer :all :exclude [update]]
            [plumbing.core :refer [fn->> map-keys for-map map-vals]]
            [schema.core :as s]

            [natsume-server.config :refer [config]]

            [mount.core :refer [defstate]])
  (:import [com.alibaba.druid.pool DruidDataSource]))

(s/defn druid-pool
  [spec :- {:subname s/Str
            :user s/Str
            :password s/Str}]
  (let [cpds (doto (DruidDataSource.)
               (.setDriverClassName "org.postgresql.Driver")
               (.setUrl (str "jdbc:postgresql:" (:subname spec)))
               (.setUsername (:user spec))
               (.setPassword (:password spec))
               (.setValidationQuery "SELECT 'x'")
               (.setMaxActive 80))]
    {:datasource cpds}))

(defstate ^{:on-reload :noop} connection :start (druid-pool (:db config)))

;; ## Database wrapper functions
(defn q
  "Wrapper function for query that sets default name transformation and optional result (row-level) transformation functions."
  [conn q & trans]
  (j/query conn
           (h/format q)
           {:row-fn (if trans
                      (reduce comp trans)
                      identity)
            :identifiers underscores->dashes
            :entities dashes->underscores}))

;; Memoized q using LRU (Least Recently Used) strategy.
;; TODO: consider using LU.

(defn i!*
  [conn tbl-name row-fn & rows]
  (let [rowseq (->> rows flatten (map #(->> %
                                            row-fn
                                            (map-keys dashes->underscores))))]
    (try (j/insert-multi! conn (dashes->underscores tbl-name) rowseq)
         (catch Exception e (do (j/print-sql-exception-chain e) (println rowseq))))))

(defn i!
  [conn tbl-name & rows]
  (let [rowseq (->> rows flatten (map #(map-keys dashes->underscores %)))]
    (try (j/insert-multi! conn (dashes->underscores tbl-name) rowseq)
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

(defn seq-par-execute!
  [conn & sql-stmts]
  (doseq [f (doall
             (map
              (fn [stmt]
                (future (e! conn [stmt])))
              (mapcat #(mapcat h/format %)
                      sql-stmts)))]
    @f))

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
                           (map (partial j/as-sql-name entities)
                                (apply concat
                                       (interpose [", "]
                                                  (map (partial interpose " ") specs))))))]
    (format "CREATE UNLOGGED TABLE %s (%s)%s"
            (j/as-sql-name entities name)
            (specs-to-string col-specs)
            table-spec-str)))

(defn create-table [conn tbl-name & specs]
  (j/db-do-commands conn (string/replace (j/create-table-ddl tbl-name specs) #"-" "_")))

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
   [:genre    :ltree    "NOT NULL"]
   [:permission :boolean]])

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
   [:string       :text    "NOT NULL"] ;; :string is a concatenation of the lemmas in the unigram
   [:orth         :text    "NOT NULL"]
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
    (e! conn (h/format (create-index :sources :genre)))

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
(def expensive-indexes
  [[:par (create-index :gram-1 :pos-1)]

   [:par (create-index :gram-2 :pos-1)]
   [:par (create-index :gram-2 :pos-2)]

   [:par (create-index :gram-3 :pos-1)]
   [:par (create-index :gram-3 :pos-2)]
   [:par (create-index :gram-3 :pos-3)]

   [:par (create-index :gram-4 :pos-1)]
   [:par (create-index :gram-4 :pos-2)]
   [:par (create-index :gram-4 :pos-3)]
   [:par (create-index :gram-4 :pos-4)]

   [:par (create-index :tokens :sentences-id)]
   [:par (create-index :tokens :lemma)]
   [:par (create-index :tokens :orth-base)]

   [:par (create-index :unigrams :sentences-id)]
   [:par (create-index :unigrams :string)]
   [:par (create-index :unigrams :orth)]
   [:par (create-index :unigrams :pos)]
   [:seq (h/raw "ANALYZE")]])

;; ### Collocation N-gram Search Tables
;;
;; Collocation n-gram search tables are divided into two schemas per n.
;; Tables beginning with search_sentences_ are for searching example sentences, while *-gram are temporary tables for holding n-gram data that are used to create search_sentences_.
(def resorted-gram-tables
  [;; 1-gram does not need sorting
   [:par (create-index :gram-1 :sentences-id)]
   [:par (add-fk :gram-1 :sentences :id)]
   [:par (create-index :gram-1 :string-1)]

   ;; 2-grams:
   [:seq (rename-table :gram-2 :gram-2-renamed)]
   [:seq (create-table-as
          :gram-2
          (-> (select :*)
              (from :gram-2-renamed)
              (order-by :pos-1 :pos-2 :string-1 :string-2 :tags-1 :tags-2 :sentences-id :begin-1)))]
   [:seq (h/raw "DROP TABLE gram_2_renamed CASCADE")]
   [:par (create-index :gram-2 :sentences-id)]
   [:par (add-fk :gram-2 :sentences :id)]
   [:par (create-index :gram-2 :pos-1)]
   [:par (create-index :gram-2 :pos-2)]
   [:par (create-index :gram-2 :string-1)]
   [:par (create-index :gram-2 :string-2)]
   ;; TODO benchmark CLUSTER

   ;; 4-grams:

   [:seq (rename-table :gram-4 :gram-4-renamed)]
   [:seq (create-table-as
          :gram-4
          (-> (select :*)
              (from :gram-4-renamed)
              (order-by :pos-1 :pos-2 :pos-3 :pos-4 :string-1 :string-2 :string-3 :string-4 :tags-1 :tags-2 :tags-3 :tags-4 :sentences-id :begin-1)))]
   [:seq (h/raw "DROP TABLE gram_4_renamed CASCADE")]
   [:par (create-index :gram-4 :sentences-id)]
   [:par (add-fk :gram-4 :sentences :id)]
   [:par (create-index :gram-4 :pos-1)]
   [:par (create-index :gram-4 :pos-2)]
   [:par (create-index :gram-4 :pos-3)]
   [:par (create-index :gram-4 :pos-4)]
   [:par (create-index :gram-4 :string-1)]
   [:par (create-index :gram-4 :string-2)]
   [:par (create-index :gram-4 :string-3)]
   [:par (create-index :gram-4 :string-4)]

   ;; Doc FIXME UNION with 1,2,3 of gram_4 -- but be careful when talking about absolute number of n-grams (emphasize we are talking about grams in 2 dependent chunks).
   ;; 3-grams:

   [:seq (rename-table :gram-3 :gram-3-renamed)]

   [:seq
    (h/raw "CREATE TABLE gram_3 AS

   WITH temporary_union AS (
   (SELECT * FROM gram_3_renamed)
   UNION
   (SELECT sentences_id, string_1, pos_1, tags_1, begin_1, end_1, string_2, pos_2, tags_2, begin_2, end_2, string_3, pos_3, tags_3, begin_3, end_3 FROM gram_4))

   SELECT * FROM temporary_union ORDER BY pos_1, pos_2, pos_3, string_1, string_2, string_3, tags_1, tags_2, tags_3, sentences_id, begin_1")]

   [:seq (h/raw "DROP TABLE gram_3_renamed CASCADE")]
   [:par (create-index :gram-3 :sentences-id)]
   [:par (add-fk :gram-3 :sentences :id)]
   [:par (create-index :gram-3 :pos-1)]
   [:par (create-index :gram-3 :pos-2)]
   [:par (create-index :gram-3 :pos-3)]
   [:par (create-index :gram-3 :string-1)]
   [:par (create-index :gram-3 :string-2)]
   [:par (create-index :gram-3 :string-3)]

   [:seq (h/raw "ANALYZE")]])

(def norm-table
  ;; ### Normalization Tables
  ;;
  ;; The norm table contains aggregate counts per genre that are used for normalizing specific counts.
  ;; Currently, token counts, sentence counts, paragraph counts, and source counts are available.
  ;;
  ;; As paragraph id's are not unique across sources in sentences table, we need to make a temporary paragraph-by-source count table.
  ;;(let [g2-keys (set (with-dbmacro (j/with-query-results res [""])))])
  ;; FIXME: anything to be done with pivot tables....?

  [[:seq (h/raw
          "CREATE TABLE genre_norm AS
   SELECT so.genre,
          sum(char_length(se.text))       AS character_count,
          sum(se.tokens)::integer         AS token_count,
          sum(se.chunks)::integer         AS chunk_count,
          count(DISTINCT se.id)::integer  AS sentences_count,
          count(DISTINCT so.id)::integer  AS sources_count
   FROM sentences AS se,
        sources AS so
   WHERE se.sources_id=so.id
   GROUP BY so.genre")]
   [:seq (create-index :genre-norm :genre :gist)]
   [:seq (create-index :genre-norm :genre)]

   ;; TODO: gram counts, etc.
   ;; Collocation n-gram counts are recorded under a different schema as the number of collocation types is more dynamic.
   [:seq (h/raw "CREATE TABLE gram_norm AS
   (SELECT g1.pos_1 AS type,
           so.genre,
           count(*) AS count,
           count(DISTINCT se.id) AS sentences_count,
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
           count(DISTINCT so.id) AS sources_count
   FROM gram_4 AS g4,
        sentences AS se,
        sources AS so
   WHERE g4.sentences_id=se.id AND se.sources_id=so.id
   GROUP BY so.genre, g4.pos_1, g4.pos_2, g4.pos_3, g4.pos_4
   ORDER BY type, so.genre, count)")]
   ;; We commonly dispatch on type, so the index here can be justified.
   ;; Genre must be profiled.
   [:par (create-index :gram-norm :type)]
   [:par (create-index :gram-norm :genre :gist)]
   [:par (create-index :gram-norm :genre)]
   [:seq (h/raw "ANALYZE")]])

(def search-table
  ;; TODO: find right place to filter rare collocations, especially on Wikipedia data (probably filter with HAVING clause).
  ;; TODO: should the token search table include computed measures like tf-idf?
  ;; TODO: cast counts as integer
  ;; FIXME 助動詞 (everything but 動詞?) should have c-form added to pos-2(1?) to differentiate between だ、な、に etc.
  [[:seq (create-table-as
          :search-tokens
          {:select [:pos-1 :pos-2 :c-form :orth-base :lemma :genre
                    [(h/call :count :pos-1) :count]
                    [(h/call :count-distinct :sentences.id) :sentences-count]
                    [(h/call :count-distinct :sources.id) :sources-count]]
           :from [:tokens :sentences :sources]
           :where [:and
                   [:= :tokens.sentences-id :sentences.id]
                   [:= :sentences.sources-id :sources.id]]
           :group-by [:pos-1 :pos-2 :c-form :orth-base :lemma :genre]
           :order-by [:lemma :orth-base :pos-1 :pos-2 :c-form :genre :count]})]
   [:par (create-index :search-tokens :genre :gist)]
   [:par (create-index :search-tokens :genre)]
   [:par (create-index :search-tokens :pos-1)]
   [:par (create-index :search-tokens :pos-2)]
   [:par (create-index :search-tokens :orth-base)]
   [:par (create-index :search-tokens :lemma)]

   ;; FIXME TODO consider adding array of sentence ids per row?
   ;; TODO need a clear search case for these (and for normal gram_X tables for that matter).
   ;; 1-gram
   [:seq
    (h/raw
     "CREATE TABLE search_gram_1 AS
   SELECT pos_1 AS type, g1.string_1, so.genre, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT so.id)::integer as sources_count
   FROM gram_1 AS g1, sentences AS se, sources AS so
   WHERE g1.sentences_id=se.id AND se.sources_id=so.id
   GROUP BY pos_1, g1.string_1, so.genre
   ORDER BY pos_1, g1.string_1, so.genre, count")]
   [:par (create-index :search-gram-1 :genre :gist)]
   [:par (create-index :search-gram-1 :genre)]
   [:par (create-index :search-gram-1 :type)]
   [:par (create-index :search-gram-1 :string-1)]

   ;; 2-grams
   [:par (h/raw "CREATE UNLOGGED TABLE search_gram_2_f_ix AS SELECT (pos_1 || '_' || pos_2) AS t, string_1, genre, count(string_2)::integer FROM gram_2, sentences, sources WHERE gram_2.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_1, genre")]
   [:par (h/raw "CREATE UNLOGGED TABLE search_gram_2_f_xi AS SELECT (pos_1 || '_' || pos_2) AS t, string_2, genre, count(string_1)::integer FROM gram_2, sentences, sources WHERE gram_2.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_2, genre")]
   [:seq (h/raw "ANALYZE search_gram_2_f_ix")] ;; FIXME
   [:par (create-index :search-gram-2-f-ix :t)]
   [:par (create-index :search-gram-2-f-ix :string-1)]
   [:par (create-index :search-gram-2-f-ix :genre)]
   [:par (create-index :search-gram-2-f-xi :t)]
   [:par (create-index :search-gram-2-f-xi :string-2)]
   [:par (create-index :search-gram-2-f-xi :genre)]
   [:seq
    (h/raw
     "CREATE TABLE search_gram_2 AS
    SELECT (pos_1 || '_' || pos_2) AS type, g2.string_1, g2.string_2, so.genre, f_ix.count AS f_ix, f_xi.count AS f_xi, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT so.id)::integer as sources_count
    FROM gram_2 AS g2, search_gram_2_f_ix AS f_ix, search_gram_2_f_xi AS f_xi, sentences AS se, sources AS so
    WHERE g2.sentences_id=se.id AND se.sources_id=so.id AND f_ix.t=(pos_1 || '_' || pos_2) AND f_xi.t=(pos_1 || '_' || pos_2) AND f_ix.string_1=g2.string_1 and f_xi.string_2=g2.string_2 AND f_ix.genre=so.genre AND f_xi.genre=so.genre
    GROUP BY pos_1, pos_2, g2.string_1, g2.string_2, so.genre, f_ix, f_xi
    ORDER BY (pos_1 || '_' || pos_2), g2.string_1, g2.string_2, so.genre, count")]

   [:seq (h/raw "DROP TABLE search_gram_2_f_ix CASCADE")]
   [:seq (h/raw "DROP TABLE search_gram_2_f_xi CASCADE")]

   #_[:seq
    (h/raw
     "CREATE TABLE search_gram_2 AS
    WITH
      f_ix AS (SELECT (pos_1 || '_' || pos_2) AS t, string_1, genre, count(string_2)::integer FROM gram_2, sentences, sources WHERE gram_2.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_1, genre),
      f_xi AS (SELECT (pos_1 || '_' || pos_2) AS t, string_2, genre, count(string_1)::integer FROM gram_2, sentences, sources WHERE gram_2.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_2, genre)
    SELECT (pos_1 || '_' || pos_2) AS type, g2.string_1, g2.string_2, so.genre, f_ix.count AS f_ix, f_xi.count AS f_xi, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT so.id)::integer as sources_count FROM gram_2 AS g2, sentences AS se, sources AS so, f_ix, f_xi
    WHERE g2.sentences_id=se.id AND se.sources_id=so.id AND f_ix.t=(pos_1 || '_' || pos_2) AND f_xi.t=(pos_1 || '_' || pos_2) AND f_ix.string_1=g2.string_1 and f_xi.string_2=g2.string_2 AND f_ix.genre=so.genre AND f_xi.genre=so.genre
    GROUP BY pos_1, pos_2, g2.string_1, g2.string_2, so.genre, f_ix, f_xi
    ORDER BY (pos_1 || '_' || pos_2), g2.string_1, g2.string_2, so.genre, count")]

   [:par (create-index :search-gram-2 :genre :gist)]
   [:par (create-index :search-gram-2 :genre)]
   [:par (create-index :search-gram-2 :type)]
   [:par (create-index :search-gram-2 :string-1)]
   [:par (create-index :search-gram-2 :string-2)]

   ;; 3-grams
   [:par (h/raw "CREATE UNLOGGED TABLE search_gram_3_f_ix AS SELECT (pos_1 || '_' || pos_2 || '_' || pos_3) AS t, string_1, genre, count(string_3)::integer FROM gram_3, sentences, sources WHERE gram_3.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_1, genre")]
   [:par (h/raw "CREATE UNLOGGED TABLE search_gram_3_f_xi AS SELECT (pos_1 || '_' || pos_2 || '_' || pos_3) AS t, string_3, genre, count(string_1)::integer FROM gram_3, sentences, sources WHERE gram_3.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_3, genre")]
   [:seq (h/raw "ANALYZE search_gram_3_f_ix")] ;; FIXME
   [:par (create-index :search-gram-3-f-ix :t)]
   [:par (create-index :search-gram-3-f-ix :string-1)]
   [:par (create-index :search-gram-3-f-ix :genre)]
   [:par (create-index :search-gram-3-f-xi :t)]
   [:par (create-index :search-gram-3-f-xi :string-3)]
   [:par (create-index :search-gram-3-f-xi :genre)]
   [:seq
    (h/raw
     "CREATE TABLE search_gram_3 AS
    SELECT (pos_1 || '_' || pos_2 || '_' || pos_3) AS type, g3.string_1, g3.string_2, g3.string_3, so.genre, f_ix.count AS f_ix, f_xi.count AS f_xi, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT so.id)::integer as sources_count
    FROM gram_3 AS g3, sentences AS se, sources AS so, search_gram_3_f_ix AS f_ix, search_gram_3_f_xi AS f_xi
    WHERE g3.sentences_id=se.id AND se.sources_id=so.id AND f_ix.t=(pos_1 || '_' || pos_2 || '_' || pos_3) AND f_xi.t=(pos_1 || '_' || pos_2 || '_' || pos_3) AND f_ix.string_1=g3.string_1 and f_xi.string_3=g3.string_3 AND f_ix.genre=so.genre AND f_xi.genre=so.genre
    GROUP BY pos_1, pos_2, pos_3, g3.string_1, g3.string_2, g3.string_3, so.genre, f_ix, f_xi
    ORDER BY (pos_1 || '_' || pos_2 || '_' || pos_3), g3.string_1, g3.string_2, g3.string_3, so.genre, count")]
   #_[:seq
    (h/raw
     "CREATE TABLE search_gram_3 AS
    WITH
      f_ix AS (SELECT (pos_1 || '_' || pos_2 || '_' || pos_3) AS t, string_1, genre, count(string_3)::integer FROM gram_3, sentences, sources WHERE gram_3.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_1, genre),
      f_xi AS (SELECT (pos_1 || '_' || pos_2 || '_' || pos_3) AS t, string_3, genre, count(string_1)::integer FROM gram_3, sentences, sources WHERE gram_3.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_3, genre)
    SELECT (pos_1 || '_' || pos_2 || '_' || pos_3) AS type, g3.string_1, g3.string_2, g3.string_3, so.genre, f_ix.count AS f_ix, f_xi.count AS f_xi, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT so.id)::integer as sources_count FROM gram_3 AS g3, sentences AS se, sources AS so, f_ix, f_xi
    WHERE g3.sentences_id=se.id AND se.sources_id=so.id AND f_ix.t=(pos_1 || '_' || pos_2 || '_' || pos_3) AND f_xi.t=(pos_1 || '_' || pos_2 || '_' || pos_3) AND f_ix.string_1=g3.string_1 and f_xi.string_3=g3.string_3 AND f_ix.genre=so.genre AND f_xi.genre=so.genre
    GROUP BY pos_1, pos_2, pos_3, g3.string_1, g3.string_2, g3.string_3, so.genre, f_ix, f_xi
    ORDER BY (pos_1 || '_' || pos_2 || '_' || pos_3), g3.string_1, g3.string_2, g3.string_3, so.genre, count")]

   [:seq (h/raw "DROP TABLE search_gram_3_f_ix CASCADE")]
   [:seq (h/raw "DROP TABLE search_gram_3_f_xi CASCADE")]

   [:par (create-index :search-gram-3 :genre :gist)]
   [:par (create-index :search-gram-3 :genre)]
   [:par (create-index :search-gram-3 :type)]
   [:par (create-index :search-gram-3 :string-1)]
   [:par (create-index :search-gram-3 :string-2)]
   [:par (create-index :search-gram-3 :string-3)]

   ;; 4-gram
   ;; Note: f-io and f-oi are fixed to string-1 and string-3 in 4-grams too (FIXME).
   [:par (h/raw "CREATE UNLOGGED TABLE search_gram_4_f_ix AS SELECT (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AS t, string_1, genre, count(string_3)::integer FROM gram_4, sentences, sources WHERE gram_4.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_1, genre")]
   [:par (h/raw "CREATE UNLOGGED TABLE search_gram_4_f_xi AS SELECT (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AS t, string_3, genre, count(string_1)::integer FROM gram_4, sentences, sources WHERE gram_4.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_3, genre")]
   [:seq (h/raw "ANALYZE search_gram_4_f_ix")] ;; FIXME
   [:par (create-index :search-gram-4-f-ix :t)]
   [:par (create-index :search-gram-4-f-ix :string-1)]
   [:par (create-index :search-gram-4-f-ix :genre)]
   [:par (create-index :search-gram-4-f-xi :t)]
   [:par (create-index :search-gram-4-f-xi :string-3)]
   [:par (create-index :search-gram-4-f-xi :genre)]
   [:seq
    (h/raw
     "CREATE TABLE search_gram_4 AS
    SELECT (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AS type, g4.string_1, g4.string_2, g4.string_3, g4.string_4, so.genre, f_ix.count AS f_ix, f_xi.count AS f_xi, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT so.id)::integer as sources_count
    FROM gram_4 AS g4, sentences AS se, sources AS so, search_gram_4_f_ix AS f_ix, search_gram_4_f_xi AS f_xi
    WHERE g4.sentences_id=se.id AND se.sources_id=so.id AND f_ix.t=(pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AND f_xi.t=(pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AND f_ix.string_1=g4.string_1 and f_xi.string_3=g4.string_3 AND f_ix.genre=so.genre AND f_xi.genre=so.genre
    GROUP BY pos_1, pos_2, pos_3, pos_4, g4.string_1, g4.string_2, g4.string_3, g4.string_4, so.genre, f_ix, f_xi
    ORDER BY (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4), g4.string_1, g4.string_2, g4.string_3, g4.string_4, so.genre, count")]

   [:seq (h/raw "DROP TABLE search_gram_4_f_ix CASCADE")]
   [:seq (h/raw "DROP TABLE search_gram_4_f_xi CASCADE")]

   #_[:seq
    (h/raw
     "CREATE TABLE search_gram_4 AS
    WITH
      f_ix AS (SELECT (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AS t, string_1, genre, count(string_3)::integer FROM gram_4, sentences, sources WHERE gram_4.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_1, genre),
      f_xi AS (SELECT (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AS t, string_3, genre, count(string_1)::integer FROM gram_4, sentences, sources WHERE gram_4.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_3, genre)
    SELECT (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AS type, g4.string_1, g4.string_2, g4.string_3, g4.string_4, so.genre, f_ix.count AS f_ix, f_xi.count AS f_xi, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT so.id)::integer as sources_count FROM gram_4 AS g4, sentences AS se, sources AS so, f_ix, f_xi
    WHERE g4.sentences_id=se.id AND se.sources_id=so.id AND f_ix.t=(pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AND f_xi.t=(pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AND f_ix.string_1=g4.string_1 and f_xi.string_3=g4.string_3 AND f_ix.genre=so.genre AND f_xi.genre=so.genre
    GROUP BY pos_1, pos_2, pos_3, pos_4, g4.string_1, g4.string_2, g4.string_3, g4.string_4, so.genre, f_ix, f_xi
    ORDER BY (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4), g4.string_1, g4.string_2, g4.string_3, g4.string_4, so.genre, count")]

   [:par (create-index :search-gram-4 :genre :gist)]
   [:par (create-index :search-gram-4 :genre)]
   [:par (create-index :search-gram-4 :type)]
   [:par (create-index :search-gram-4 :string-1)]
   [:par (create-index :search-gram-4 :string-2)]
   [:par (create-index :search-gram-4 :string-3)]
   [:par (create-index :search-gram-4 :string-4)]
   [:seq (h/raw "ANALYZE")]])

(defn create-search-tables! [conn & {:keys [resort?] :or {resort? true}}]
  (let [stmts-vec
        (if resort?
          [expensive-indexes resorted-gram-tables norm-table search-table]
          [norm-table search-table])]
    (doseq [stmts stmts-vec
            part-stmts (partition-by first stmts)]
      (case (-> part-stmts ffirst)
        :par (seq-par-execute! conn (map second part-stmts))
        :seq (seq-execute! conn (map second part-stmts))))))

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

(defstate !norm-map
  :start (when (:server config)
           {:sources    (seq-to-tree (q connection (-> (select :genre [:sources-count :count]) (from :genre-norm)) genre-ltree-transform))
            :sentences  (seq-to-tree (q connection (-> (select :genre [:sentences-count :count]) (from :genre-norm)) genre-ltree-transform))
            :chunks     (seq-to-tree (q connection (-> (select :genre [:chunk-count :count]) (from :genre-norm)) genre-ltree-transform))
            :tokens     (seq-to-tree (q connection (-> (select :genre [:token-count :count]) (from :genre-norm)) genre-ltree-transform))
            :characters (seq-to-tree (q connection (-> (select :genre [:character-count :count]) (from :genre-norm)) genre-ltree-transform))}))
(defstate !genre-names
  :start (when (:server config) (->> !norm-map :sources :children (map :name) set)))
(defstate !genre-tokens-map
  :start (when (:server config) (->> !norm-map :tokens :children (map (juxt :name :count)) (into {}))))

;; Should contain all totals in a map by collocation type (n-gram size is determined by type) and genre.
(defstate !gram-totals
  :start (when (:server config)
           (let [records (q connection (-> (select :*) (from :gram-norm)) #(update-in % [:type] underscores->dashes))]
             (->> records
                  (map #(update-in % [:genre] ltree->seq))
                  (group-by :type)
                  (map-vals #(seq-to-tree % {:merge-fns {:count +
                                                         :sentences-count +
                                                         :sources-count +}}))))))
(defstate !gram-types
  :start (when (:server config)
           (set (q connection (-> (select :type) (from :gram-norm)) underscores->dashes :type))))
(defstate !tokens-by-gram
  :start (when (:server config)
           (map-vals (fn [x] (reduce #(+ %1 (-> %2 val :count)) 0 x))
                     (group-by #(let [wcs (clojure.string/split (name (key %)) #"-")
                                      aux-count (count (filter (fn [wc] (= "auxiliary" wc)) wcs))]
                                  (- (count wcs) aux-count))
                               !gram-totals))))

;; Component

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

(defstate ^{:on-reload :noop}
  database-init :start
  (when (:clean config)
    (drop-all-cascade! connection)
    (create-tables-and-indexes! connection)))
