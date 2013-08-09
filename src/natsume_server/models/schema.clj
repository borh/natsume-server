(ns natsume-server.models.schema
  (:require [clojure.string :as string]
            [clojure.java.jdbc :as j]
            [clojure.java.jdbc.sql :as sql]
            [honeysql.core :as h]
            [honeysql.format :as fmt]
            [honeysql.helpers :refer :all]

            [plumbing.core :refer [for-map]]
            [natsume-server.models.sql-helpers :refer :all]
            [natsume-server.utils.naming :refer [dashes->underscores underscores->dashes]]))

;; ## JDBC naming strategy
(def naming-strategy ; JDBC.
  {:entity dashes->underscores :keyword underscores->dashes})

(defn create-table [name & specs]
  (e! [(string/replace (apply j/create-table-ddl name specs) #"-" "_")]))

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
   [:id                "serial"  "PRIMARY KEY"]
   [:text              "text"    "NOT NULL"]
   [:sentence-order-id "integer" "NOT NULL"]
   [:paragraph-order-id "integer" "NOT NULL"]
   [:sources-id        "integer" "REFERENCES sources(id)"]
   [:tags              "text[]"]
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
   [:goshu        :text    "NOT NULL"]])

(def n-gram-schemas
  (let [sentences-column [:sentences-id :integer "NOT NULL" "REFERENCES sentences(id)"]
        gram-columns (for [order (range 1 5)]
                       [[(str "string_" order) :text     "NOT NULL"]
                        [(str "pos_"    order) :text     "NOT NULL"]
                        [(str "tags_"   order) "text[]"  "NOT NULL"] ;; consider HSTORE? (for aggregate frequencies)
                        [(str "begin_"  order) :smallint "NOT NULL"]
                        [(str "end_"    order) :smallint "NOT NULL"]])]
    (for-map [n (range 2 5)]
             (keyword (str "gram-" n))
             (concat [(keyword (str "gram-" n))] (conj (apply concat (take n gram-columns)) sentences-column)))))

(defn- create-tables-and-indexes!
  "Create tables and indexes for Natsume.

  TODO benchmark w/o indexes (i.e. create indexes only after all data has been inserted"
  []
  (do

    ;; TODO need to add information from CopyRight_Annotation.txt as per BCCWJ usage guidelines.
    (sql/create-table
     :sources
     [:id       :serial   "PRIMARY KEY"]
     [:title    :text     "NOT NULL"]
     [:author   :text]
     [:year     :smallint "NOT NULL"]
     [:basename :text     "NOT NULL"]
     [:genre    :ltree    "NOT NULL"])
    (sql/do-commands "CREATE INDEX idx_sources_genre_gist ON sources USING GIST(genre)")
    (sql/do-commands "CREATE INDEX idx_sources_genre      ON sources USING btree(genre)")

    (sql/create-table
     :sentences
     [:id                "serial"  "PRIMARY KEY"]
     [:text              "text"    "NOT NULL"]
     [:sentence-order-id "integer" "NOT NULL"]
     [:paragraph-order-id "integer" "NOT NULL"]
     [:sources-id        "integer" "REFERENCES sources(id)"]
     [:tags              "text[]"]
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
     [:chunk-depth :real])
    (sql/do-commands "CREATE INDEX idx_sentences_sources_id ON sentences (sources_id)")

    ;; Append only long format.
    (sql/create-table
     :tokens
     [:sentences-id :integer "NOT NULL" "REFERENCES sentences(id)"]
     [:pos1         :text    "NOT NULL"]
     [:pos2         :text    "NOT NULL"]
     [:orthBase     :text    "NOT NULL"]
     [:lemma        :text    "NOT NULL"])

    ;; 2, 3 and 4 collocation gram tables.
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

;; ## Search Schemas
;;
;; Search schemas are created from the above tables to enable fast searching with common queries.
;; They must be created after processing.
;; TODO convert to migrations (https://github.com/macourtney/drift / https://github.com/pjstadig/migratus) or clojure.java.jdbc DSL
;; FIXME !Profile if having indexes to make new table is faster than not creating them!
;; TODO concurrent index creation
(def expensive-indexes
  ["CREATE INDEX idx_gram_2_pos_1 ON gram_2 (pos_1)"
   "CREATE INDEX idx_gram_2_pos_2 ON gram_2 (pos_2)"

   "CREATE INDEX idx_gram_3_pos_1 ON gram_3 (pos_1)"
   "CREATE INDEX idx_gram_3_pos_2 ON gram_3 (pos_2)"
   "CREATE INDEX idx_gram_3_pos_3 ON gram_3 (pos_3)"

   "CREATE INDEX idx_gram_4_pos_1 ON gram_4 (pos_1)"
   "CREATE INDEX idx_gram_4_pos_2 ON gram_4 (pos_2)"
   "CREATE INDEX idx_gram_4_pos_3 ON gram_4 (pos_3)"
   "CREATE INDEX idx_gram_4_pos_4 ON gram_4 (pos_4)"
   "ANALYZE"])

;; ### Collocation N-gram Search Tables
;;
;; Collocation n-gram search tables are divided into two schemas per n.
;; Tables beginning with search_sentences_ are for searching example sentences, while
(def resorted-gram-tables
  [;; 2-grams:
   "ALTER TABLE gram_2 RENAME TO gram_2_renamed"
   "CREATE TABLE gram_2 AS
   SELECT * FROM gram_2_renamed
   ORDER BY pos_1, pos_2, string_1, string_2, tags_1, tags_2, sentences_id, begin_1"
   "DROP TABLE gram_2_renamed CASCADE"
   "CREATE INDEX idx_gram_2_sentences_id ON gram_2 (sentences_id)"
   "ALTER TABLE gram_2 ADD CONSTRAINT idx_gram_2_sentences_id_fk FOREIGN KEY (sentences_id) REFERENCES sentences (id)"
   "CREATE INDEX idx_gram_2_pos_1 ON gram_2 (pos_1)"
   "CREATE INDEX idx_gram_2_pos_2 ON gram_2 (pos_2)"
   "CREATE INDEX idx_gram_2_string_1 ON gram_2 (string_1)"
   "CREATE INDEX idx_gram_2_string_2 ON gram_2 (string_2)"
   ;; TODO benchmark CLUSTER

   ;; Doc FIXME UNION with 1,2,3 of gram_4 -- but be careful when talking about absolute number of n-grams (emphasize we are talking about grams in 2 dependent chunks).
   ;; 3-grams:

   "ALTER TABLE gram_3 RENAME TO gram_3_renamed"
   "ALTER TABLE gram_4 RENAME TO gram_4_renamed"
   "CREATE TABLE gram_3 AS

   WITH temporary_union AS (
   (SELECT * FROM gram_3_renamed)
   UNION
   (SELECT sentences_id, string_1, pos_1, tags_1, begin_1, end_1, string_2, pos_2, tags_2, begin_2, end_2, string_3, pos_3, tags_3, begin_3, end_3 FROM gram_4_renamed))

   SELECT * FROM temporary_union ORDER BY pos_1, pos_2, pos_3, string_1, string_2, string_3, tags_1, tags_2, tags_3, sentences_id, begin_1"

   "DROP TABLE gram_3_renamed CASCADE"
   "CREATE INDEX idx_gram_3_sentences_id ON gram_3 (sentences_id)"
   "ALTER TABLE gram_3 ADD CONSTRAINT idx_gram_3_sentences_id_fk FOREIGN KEY (sentences_id) REFERENCES sentences (id)"
   "CREATE INDEX idx_gram_3_pos_1 ON gram_3 (pos_1)"
   "CREATE INDEX idx_gram_3_pos_2 ON gram_3 (pos_2)"
   "CREATE INDEX idx_gram_3_pos_3 ON gram_3 (pos_3)"
   "CREATE INDEX idx_gram_3_string_1 ON gram_3 (string_1)"
   "CREATE INDEX idx_gram_3_string_2 ON gram_3 (string_2)"
   "CREATE INDEX idx_gram_3_string_3 ON gram_3 (string_3)"

   ;; 4-grams:

   "CREATE TABLE gram_4 AS
   SELECT * FROM gram_4_renamed ORDER BY pos_1, pos_2, pos_3, pos_4, string_1, string_2, string_3, string_4, tags_1, tags_2, tags_3, tags_4, sentences_id, begin_1"

   "DROP TABLE gram_4_renamed CASCADE"
   "CREATE INDEX idx_gram_4_sentences_id ON gram_4 (sentences_id)"
   "ALTER TABLE gram_4 ADD CONSTRAINT idx_gram_4_sentences_id_fk FOREIGN KEY (sentences_id) REFERENCES sentences (id)"
   "CREATE INDEX idx_gram_4_pos_1 ON gram_4 (pos_1)"
   "CREATE INDEX idx_gram_4_pos_2 ON gram_4 (pos_2)"
   "CREATE INDEX idx_gram_4_pos_3 ON gram_4 (pos_3)"
   "CREATE INDEX idx_gram_4_pos_4 ON gram_4 (pos_4)"
   "CREATE INDEX idx_gram_4_string_1 ON gram_4 (string_1)"
   "CREATE INDEX idx_gram_4_string_2 ON gram_4 (string_2)"
   "CREATE INDEX idx_gram_4_string_3 ON gram_4 (string_3)"
   "CREATE INDEX idx_gram_4_string_4 ON gram_4 (string_4)"
   "ANALYZE"])

(def norm-table
  ;; ### Normalization Tables
  ;;
  ;; The norm table contains aggregate counts per genre that are used for normalizing specific counts.
  ;; Currently, token counts, sentence counts, paragraph counts, and source counts are available.
  ;;
  ;; As paragraph id's are not unique across sources in sentences table, we need to make a temporary paragraph-by-source count table.
  ;;(let [g2-keys (set (with-dbmacro (sql/with-query-results res [""])))])
  ;; FIXME: anything to be done with pivot tables....?

  #_"     (SELECT max(paragraph_order_id) AS count, sources_id -- or max(paragraph_order_id)
      FROM sentences
      GROUP BY sources_id
      ORDER BY sources_id)"

  ["CREATE TABLE genre_norm AS
   WITH paragraph_table AS
   (SELECT sum(count) AS count, genre FROM (SELECT max(paragraph_order_id) AS count, sources.id, genre FROM sentences, sources WHERE sentences.sources_id=sources.id GROUP BY sources.id, sources.genre) AS count GROUP BY genre)
   SELECT so.genre,
          sum(se.tokens)::integer         AS token_count,
          sum(se.chunks)::integer         AS chunk_count,
          count(DISTINCT se.id)::integer  AS sentences_count,
          pt.count::integer AS paragraphs_count, -- FIXME DISTINCT is wrong, normal sum is wrong
          count(DISTINCT so.id)::integer  AS sources_count
   FROM sentences AS se,
        sources AS so,
        paragraph_table AS pt
   WHERE se.sources_id=so.id AND so.genre=pt.genre
   GROUP BY so.genre, pt.count"
   ;; No indexes for now as it is a small table.

   ;; TODO: gram counts, etc.
   ;; Collocation n-gram counts are recorded under a different schema as the number of collocation types is more dynamic.
   "CREATE TABLE gram_norm AS
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
   ORDER BY type, so.genre, count)"
   ;; We commonly dispatch on type, so the index here can be justified.
   ;; Genre must be profiled.
   "CREATE INDEX idx_gram_norm_type ON gram_norm (type)"
   "ANALYZE"])

(def search-table
  ;; TODO: find right place to filter rare collocations, especially on Wikipedia data (probably filter with HAVING clause).
  [#_"CREATE TABLE search_tokens AS SELECT pos_1, pos_2, orth_base, lemma, genre, count(*) AS count FROM tokens AS t, sentences AS se, sources AS so WHERE t
.sentences_id=se.id AND se.sources_id=so.id GROUP BY pos_1, pos_2, orth_base, lemma, genre ORDER BY lemma, orth_base, pos_1, pos_2, count"
   "CREATE TABLE search_tokens AS SELECT pos_1, pos_2, orth_base, lemma, genre, count(pos_1)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT se.paragraph_order_id)::integer as paragraphs_count, count(DISTINCT so.id)::integer as sources_count FROM tokens AS t, sentences AS se, sources AS so WHERE t.sentences_id=se.id AND se.sources_id=so.id GROUP BY pos_1, pos_2, orth_base, lemma, genre ORDER BY lemma, orth_base, pos_1, pos_2, genre, count"
   "CREATE INDEX idx_search_tokens_genre_gist ON search_tokens USING GIST(genre)"
   "CREATE INDEX idx_search_tokens_genre      ON search_tokens USING btree(genre)"
   "CREATE INDEX idx_search_tokens_pos_1       ON search_tokens (pos_1)"
   "CREATE INDEX idx_search_tokens_pos_2       ON search_tokens (pos_2)"
   "CREATE INDEX idx_search_tokens_orth_base   ON search_tokens (orth_base)"
   "CREATE INDEX idx_search_tokens_lemma      ON search_tokens (lemma)"

   ;; TODO need a clear search case for these (and for normal gram_X tables for that matter).
   ;; FIXME paragraph_counts are probably wrong
   "CREATE TABLE search_gram_2 AS
   SELECT (pos_1 || '_' || pos_2) AS type, string_1, string_2, genre, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT se.paragraph_order_id)::integer as paragraphs_count, count(DISTINCT so.id)::integer as sources_count FROM gram_2 AS g2, sentences AS se, sources AS so WHERE g2.sentences_id=se.id AND se.sources_id=so.id GROUP BY pos_1, pos_2, string_1, string_2, genre ORDER BY type, string_1, string_2, genre, count"
   "CREATE INDEX idx_search_gram_2_genre_gist ON search_gram_2 USING GIST(genre)"
   "CREATE INDEX idx_search_gram_2_genre      ON search_gram_2 USING btree(genre)"
   "CREATE INDEX idx_search_gram_2_type       ON search_gram_2 (type)"
   "CREATE INDEX idx_search_gram_2_string_1   ON search_gram_2 (string_1)"
   "CREATE INDEX idx_search_gram_2_string_2   ON search_gram_2 (string_2)"

   "CREATE TABLE search_gram_3 AS
   SELECT (pos_1 || '_' || pos_2 || '_' || pos_3) AS type, string_1, string_2, string_3, genre, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT se.paragraph_order_id)::integer as paragraphs_count, count(DISTINCT so.id)::integer as sources_count FROM gram_3 AS g3, sentences AS se, sources AS so WHERE g3.sentences_id=se.id AND se.sources_id=so.id GROUP BY pos_1, pos_2, pos_3, string_1, string_2, string_3, genre ORDER BY type, string_1, string_2, string_3, genre, count"
   "CREATE INDEX idx_search_gram_3_genre_gist ON search_gram_3 USING GIST(genre)"
   "CREATE INDEX idx_search_gram_3_genre      ON search_gram_3 USING btree(genre)"
   "CREATE INDEX idx_search_gram_3_type       ON search_gram_3 (type)"
   "CREATE INDEX idx_search_gram_3_string_1   ON search_gram_3 (string_1)"
   "CREATE INDEX idx_search_gram_3_string_2   ON search_gram_3 (string_2)"
   "CREATE INDEX idx_search_gram_3_string_3   ON search_gram_3 (string_3)"

   "ANALYZE"])

(defn create-search-tables! []
  (with-dbmacro-ex #(println % (.getNextException %)) (apply sql/do-commands (concat expensive-indexes resorted-gram-tables norm-table search-table ))))

;; ## Clean-slate database functions

(defn drop-all-cascade!
  "Drop cascade all tables and indexes."
  [& exclusions]
  (let [exclude-set (->> exclusions
                         (map dashes->underscores)
                         (map name)
                         (map re-pattern)
                         set)]
    (with-dbmacro
      (sql/with-query-results stmts
        ["SELECT 'DROP TABLE \"' || tablename || '\" CASCADE' FROM pg_tables WHERE schemaname = 'public'"]
        (doseq [stmt (->> stmts
                          (map vals)
                          flatten
                          (remove #(some (fn [pattern] (re-seq pattern %)) exclude-set)))]
          (sql/do-commands stmt))))))

;; ## Final database initialization

(defn init-database!
  "Initialize Natsume database.
   Creates schema and indexes.
   Supports optional parameters:
   destructive?: recreates all tables (destructive, use with caution!)"
  []
  (with-db-tx create-tables-and-indexes!))
