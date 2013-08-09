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
    (apply create-table sources-schema)
    (e! (h/format (create-index :sources :genre :gist)))
    (e! (h/format (create-index :sources :genre :btree)))

    (apply create-table sentences-schema)
    (e! (h/format (create-index :sentences :sources-id)))
    (e! (h/format (add-fk :sentences :sources :id)))
    ;;(j/do-commands "CREATE INDEX idx_sentences_sources_id ON sentences (sources_id)")

    ;; Append only long format.
    (apply create-table tokens-schema)

    ;; 2, 3 and 4 collocation gram tables.
    (doseq [[tbl-name tbl-schema] n-gram-schemas]
      (apply create-table tbl-schema))))

;; ## Search Schemas
;;
;; Search schemas are created from the above tables to enable fast searching with common queries.
;; They must be created after processing.
;; TODO convert to migrations (https://github.com/macourtney/drift / https://github.com/pjstadig/migratus) or clojure.java.jdbc DSL
;; FIXME !Profile if having indexes to make new table is faster than not creating them!
;; TODO concurrent index creation
(def expensive-indexes
  [(create-index :gram-2 :pos-1)
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
   (h/raw "ANALYZE")])

;; ### Collocation N-gram Search Tables
;;
;; Collocation n-gram search tables are divided into two schemas per n.
;; Tables beginning with search_sentences_ are for searching example sentences, while
(def resorted-gram-tables
  [;; 2-grams:
   (rename-table :gram-2 :gram-2-renamed)
   (create-table-as :gram-2
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
   (create-table-as :gram-4
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
   #_(create-table-as :gram-3
                    (h/format (union (-> (select :*)
                                         (from :gram-3-renamed))
                                     (-> (select :*)
                                         (from :gram-4)))))
   (h/raw "CREATE TABLE gram_3 AS

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

  #_"     (SELECT max(paragraph_order_id) AS count, sources_id -- or max(paragraph_order_id)
      FROM sentences
      GROUP BY sources_id
      ORDER BY sources_id)"

  [(h/raw
    "CREATE TABLE genre_norm AS
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
   GROUP BY so.genre, pt.count")
   ;; No indexes for now as it is a small table.

   ;; TODO: gram counts, etc.
   ;; Collocation n-gram counts are recorded under a different schema as the number of collocation types is more dynamic.
   (h/raw "CREATE TABLE gram_norm AS
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
  [(create-table-as :search-tokens
                    {:select [:pos-1 :pos-2 :orth-base :lemma :genre
                              [(h/call :count :pos-1) :count]
                              [(h/call :count-distinct :sentences.id) :sentences-count]
                              [(h/call :count-distinct :sentences.paragraph-order-id) :paragraphs-count]
                              [(h/call :count-distinct :sources.id) :sources-count]]
                     :from [:tokens :sentences :sources]
                     :where [:and
                             [:= :tokens.sentences-id :sentences.id]
                             [:= :sentences.sources-id :sources.id]]
                     :group-by [:pos-1 :pos-2 :orth-base :lemma :genre]
                     :order-by [:lemma :orth-base :pos-1 :pos-2 :genre :count]})
   (create-index :search-tokens :genre :gist)
   (create-index :search-tokens :genre)
   (create-index :search-tokens :pos-1)
   (create-index :search-tokens :pos-2)
   (create-index :search-tokens :orth-base)
   (create-index :search-tokens :lemma)

   ;; FIXME TODO consider adding array of sentence ids per row?
   ;; TODO need a clear search case for these (and for normal gram_X tables for that matter).
   ;; FIXME paragraph_counts are probably wrong
   (h/raw
    "CREATE TABLE search_gram_2 AS
     WITH
       f_ix AS (SELECT (pos_1 || '_' || pos_2) AS t, string_1, genre, count(string_2)::integer FROM gram_2, sentences, sources WHERE gram_2.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_1, genre),
       f_xi AS (SELECT (pos_1 || '_' || pos_2) AS t, string_2, genre, count(string_1)::integer FROM gram_2, sentences, sources WHERE gram_2.sentences_id=sentences.id AND sentences.sources_id=sources.id GROUP BY t, string_2, genre)
    SELECT (pos_1 || '_' || pos_2) AS type, g2.string_1, g2.string_2, so.genre, f_ix.count AS f_ix, f_xi.count AS f_xi, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT se.paragraph_order_id)::integer as paragraphs_count, count(DISTINCT so.id)::integer as sources_count FROM gram_2 AS g2, sentences AS se, sources AS so, f_ix, f_xi WHERE g2.sentences_id=se.id AND se.sources_id=so.id AND f_ix.t=(pos_1 || '_' || pos_2) AND f_xi.t=(pos_1 || '_' || pos_2) AND f_ix.string_1=g2.string_1 and f_xi.string_2=g2.string_2 AND f_ix.genre=so.genre AND f_xi.genre=so.genre GROUP BY pos_1, pos_2, g2.string_1, g2.string_2, so.genre, f_ix, f_xi ORDER BY (pos_1 || '_' || pos_2), g2.string_1, g2.string_2, so.genre, count")

   #_(h/raw "CREATE TABLE search_gram_2 AS
   SELECT (pos_1 || '_' || pos_2) AS type, string_1, string_2, genre,
   (SELECT count(*) FROM gram_2, sentences, sources WHERE gram_2.sentences_id=sentences.id AND sentences.sources_id=sources.id AND gram_2.string_1=g2.string_1 AND sources.genre=so.genre)::integer AS f_io,
   (SELECT count(*) FROM gram_2, sentences, sources WHERE gram_2.sentences_id=sentences.id AND sentences.sources_id=sources.id AND gram_2.string_2=g2.string_2 AND sources.genre=so.genre)::integer AS f_oi,
   count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT se.paragraph_order_id)::integer as paragraphs_count, count(DISTINCT so.id)::integer as sources_count FROM gram_2 AS g2, sentences AS se, sources AS so WHERE g2.sentences_id=se.id AND se.sources_id=so.id GROUP BY pos_1, pos_2, string_1, string_2, genre ORDER BY type, string_1, string_2, genre, count")
   (create-index :search-gram-2 :genre :gist)
   (create-index :search-gram-2 :genre)
   (create-index :search-gram-2 :type)
   (create-index :search-gram-2 :string-1)
   (create-index :search-gram-2 :string-2)

   (create-table-as :gram-3-ct-string-1
                    {:select [:pos-1 :pos-2 :pos-3 :string-1 :genre
                              (h/raw "count(string_3)::integer")]
                     :from [:gram-3 :sentences :sources]
                     :where [:and
                             [:= :gram-3.sentences-id :sentences.id]
                             [:= :sentences.sources-id :sources.id]]
                     :group-by [:pos-1 :pos-2 :pos-3 :string-1 :genre]
                     :order-by [:pos-1 :pos-2 :pos-3 :string-1 :genre]})
   (create-index :gram-3-ct-string-1 :pos-1)
   (create-index :gram-3-ct-string-1 :pos-2)
   (create-index :gram-3-ct-string-1 :pos-3)
   (create-index :gram-3-ct-string-1 :string-1)
   (create-index :gram-3-ct-string-1 :genre)

   (create-table-as :gram-3-ct-string-3
                    {:select [:pos-1 :pos-2 :pos-3 :string-3 :genre
                              (h/raw "count(string_1)::integer")]
                     :from [:gram-3 :sentences :sources]
                     :where [:and
                             [:= :gram-3.sentences-id :sentences.id]
                             [:= :sentences.sources-id :sources.id]]
                     :group-by [:pos-1 :pos-2 :pos-3 :string-3 :genre]
                     :order-by [:pos-1 :pos-2 :pos-3 :string-3 :genre]})
   (create-index :gram-3-ct-string-3 :pos-1)
   (create-index :gram-3-ct-string-3 :pos-2)
   (create-index :gram-3-ct-string-3 :pos-3)
   (create-index :gram-3-ct-string-3 :string-3)
   (create-index :gram-3-ct-string-3 :genre)

   (create-table-as :search-gram-3
                    {:select [[(h/raw "gram_3.pos_1 || '_' || gram_3.pos_2 || '_' || gram_3.pos_3") :type]
                              :gram-3.string-1 :gram-3.string-2 :gram-3.string-3
                              :sources.genre
                              [:gram-3-ct-string-1.count :f-ix]
                              [:gram-3-ct-string-3.count :f-xi]
                              (h/raw "count(*)::integer")
                              [(h/raw "count(DISTINCT sentences.id)::integer") :sentences-count]
                              [(h/raw "count(DISTINCT sentences.paragraph_order_id)::integer") :paragraphs-count]
                              [(h/raw "count(DISTINCT sources.id)::integer") :sources-count]]
                     :from [:gram-3 :gram-3-ct-string-1 :gram-3-ct-string-3 :sentences :sources]
                     :where [:and
                             [:= :gram-3.sentences-id :sentences.id]
                             [:= :sentences.sources-id :sources.id]

                             [:= :gram-3-ct-string-1.pos-1 :gram-3.pos-1]
                             [:= :gram-3-ct-string-1.pos-2 :gram-3.pos-2]
                             [:= :gram-3-ct-string-1.pos-3 :gram-3.pos-3]
                             [:= :gram-3-ct-string-1.genre :sources.genre]
                             [:= :gram-3-ct-string-1.string-1 :gram-3.string-1]

                             [:= :gram-3-ct-string-3.pos-1 :gram-3.pos-1]
                             [:= :gram-3-ct-string-3.pos-2 :gram-3.pos-2]
                             [:= :gram-3-ct-string-3.pos-3 :gram-3.pos-3]
                             [:= :gram-3-ct-string-3.genre :sources.genre]
                             [:= :gram-3-ct-string-3.string-3 :gram-3.string-3]]
                     :group-by [:gram-3.pos-1 :gram-3.pos-2 :gram-3.pos-3 :gram-3.string-1 :gram-3.string-2 :gram-3.string-3 :sources.genre :f-ix :f-xi]
                     :order-by [:gram-3.pos-1 :gram-3.pos-2 :gram-3.pos-3 :gram-3.string-1 :gram-3.string-2 :gram-3.string-3 :sources.genre :count]})

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
    SELECT (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AS type, g4.string_1, g4.string_2, g4.string_3, g4.string_4, so.genre, f_ix.count AS f_ix, f_xi.count AS f_xi, count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT se.paragraph_order_id)::integer as paragraphs_count, count(DISTINCT so.id)::integer as sources_count FROM gram_4 AS g4, sentences AS se, sources AS so, f_ix, f_xi WHERE g4.sentences_id=se.id AND se.sources_id=so.id AND f_ix.t=(pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AND f_xi.t=(pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AND f_ix.string_1=g4.string_1 and f_xi.string_3=g4.string_3 AND f_ix.genre=so.genre AND f_xi.genre=so.genre GROUP BY pos_1, pos_2, pos_3, pos_4, g4.string_1, g4.string_2, g4.string_3, g4.string_4, so.genre, f_ix, f_xi ORDER BY (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4), g4.string_1, g4.string_2, g4.string_3, g4.string_4, so.genre, count")

   #_(h/raw "CREATE TABLE search_gram_4 AS
   SELECT (pos_1 || '_' || pos_2 || '_' || pos_3 || '_' || pos_4) AS type, string_1, string_2, string_3, string_4, genre,
   (SELECT count(*) FROM gram_4, sentences, sources WHERE gram_4.sentences_id=sentences.id AND sentences.sources_id=sources.id AND gram_4.string_1=g4.string_1 AND sources.genre=so.genre)::integer AS f_io,
   (SELECT count(*) FROM gram_4, sentences, sources WHERE gram_4.sentences_id=sentences.id AND sentences.sources_id=sources.id AND gram_4.string_3=g4.string_3 AND sources.genre=so.genre)::integer AS f_oi,
   count(*)::integer AS count, count(DISTINCT se.id)::integer as sentences_count, count(DISTINCT se.paragraph_order_id)::integer as paragraphs_count, count(DISTINCT so.id)::integer as sources_count FROM gram_4 AS g4, sentences AS se, sources AS so WHERE g4.sentences_id=se.id AND se.sources_id=so.id GROUP BY pos_1, pos_2, pos_3, pos_4, string_1, string_2, string_3, string_4, genre ORDER BY type, string_1, string_2, string_3, string_4, genre, count")
   (create-index :search-gram-4 :genre :gist)
   (create-index :search-gram-4 :genre)
   (create-index :search-gram-4 :type)
   (create-index :search-gram-4 :string-1)
   (create-index :search-gram-4 :string-2)
   (create-index :search-gram-4 :string-3)
   (create-index :search-gram-4 :string-4)
   (h/raw "ANALYZE")])

(defn create-search-tables! [& {:keys [resort?] :or {resort? true}}]
  (if resort? ;; FIXME one big transaction might be overkill?
    (seq-execute! expensive-indexes resorted-gram-tables norm-table search-table)
    (seq-execute! norm-table search-table)))

(defn drop-search-tables! []
  (with-db
    (j/drop-table :genre_norm)
    (j/drop-table :gram_norm)
    (j/drop-table :search_tokens)
    (j/drop-table :search_gram_2)
    (j/drop-table :search_gram_3)))

;; ## Clean-slate database functions

(defn drop-all-cascade!
  "Drop cascade all tables and indexes."
  [& exclusions]
  (let [exclude-set (->> exclusions
                         (map dashes->underscores)
                         (map name)
                         (map re-pattern)
                         set)]
    (with-db
      (j/with-query-results stmts
        ["SELECT 'DROP TABLE \"' || tablename || '\" CASCADE' FROM pg_tables WHERE schemaname = 'public'"]
        (doseq [stmt (->> stmts
                          (map vals)
                          flatten
                          (remove #(some (fn [pattern] (re-seq pattern %)) exclude-set)))]
          (j/do-commands stmt))))))

;; ## Final database initialization

(defn init-database!
  "Initialize Natsume database.
   Creates schema and indexes.
   Supports optional parameters:
   destructive?: recreates all tables (destructive, use with caution!)"
  []
  (create-tables-and-indexes!))
