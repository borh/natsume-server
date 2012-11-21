;; # Database connectivity and utility functions
;; Natsume uses the [`korma`](http://sqlkorma.com/) library to access
;; the PostgreSQL database. The database user, password and schema
;; must be set up in a separate step outlined in the Deployment README.
(ns natsume-server.database
  (:use [korma.db]
        [korma.core])
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as sql]
            [clojure.string :as string]
            [taoensso.timbre :as log]
            [natsume-server.log-config :as lc]))

(lc/setup-log log/config :error)

;; # Local config
(def local-config
  "Loads config from the 'local-config.clj' file in the project root directory as well as environment
   variables (prefixed with 'NATSUME_').
   NOTE: Environment variables override file variables override source-file variables."
  (try
    (with-open [r (io/reader "local-config.clj")]
      (read (java.io.PushbackReader. r)))
    (catch Exception e ; TODO find out how to check for FileNotFoundException exactly
      (into {} (filter
                (comp nil?)
                (map #(vector (keyword (string/lower-case (string/replace % "NATSUME_" "")))
                              (get (System/getenv) %))
                     ["NATSUME_SUBNAME" "NATSUME_USER" "NATSUME_PASSWORD"]))))))

;; # PostgreSQL setup.
;;
;; Needs the following to be set up on the PostgreSQL server:
;;
;;     CREATE USER natsumedev WITH NOSUPERUSER NOCREATEDB ENCRYPTED PASSWORD '';
;;     CREATE DATABASE natsumedev WITH OWNER natsumedev ENCODING 'UNICODE';
;;
;; :subname, :user and :password should match that found in the following dbspec:
(def natsume-dbspec
  (merge
   {:classname   "org.postgresql.Driver"
    :subprotocol "postgresql"
    :subname     "//localhost:5432/natsumedev"
    :user        "natsumedev"
    :password    ""}
   local-config))

(defdb natsume-db natsume-dbspec)

(defn drop-all-indexes
  "Remove indexes so we can reset tables."
  []
  (sql/do-commands
   "DROP INDEX IF EXISTS idx_npv_mappings_npv_id"
   "DROP INDEX IF EXISTS idx_npv_mappings_npv_positions_id"
   "DROP INDEX IF EXISTS idx_npv_mappings_sentences_id"
   "DROP INDEX IF EXISTS idx_sentences_sources_id"
   "DROP INDEX IF EXISTS idx_orthbases_lemmas_id"
   "DROP INDEX IF EXISTS idx_orthbases_name"
   "DROP INDEX IF EXISTS idx_orthbases_genres_freqs_orthbases_id"
   "DROP INDEX IF EXISTS idx_orthbases_genres_freqs_genres_id"
   "DROP INDEX IF EXISTS idx_lemmas_pos"
   "DROP INDEX IF EXISTS idx_lemmas_name"
   "DROP INDEX IF EXISTS idx_sources_genres_id"
   "DROP INDEX IF EXISTS idx_sources_subgenres_id"
   "DROP INDEX IF EXISTS idx_sources_subsubgenres_id"
   "DROP INDEX IF EXISTS idx_sources_subsubsubgenres_id"
   "DROP INDEX IF EXISTS idx_npv_noun"
   "DROP INDEX IF EXISTS idx_npv_particle"
   "DROP INDEX IF EXISTS idx_npv_verb"))

(defn drop-all-tables
  "Reset all tables."
  []
  (sql/do-commands
   "DROP TABLE IF EXISTS sources_readability"
   "DROP TABLE IF EXISTS paragraph_readability"
   "DROP TABLE IF EXISTS sentences_readability"
   "DROP TABLE IF EXISTS npv_mappings"
   "DROP TABLE IF EXISTS sentences"
   "DROP TABLE IF EXISTS sources"
   "DROP TABLE IF EXISTS orthbases_genres_freqs"
   "DROP TABLE IF EXISTS orthbases"
   "DROP TABLE IF EXISTS lemmas"
   "DROP TABLE IF EXISTS npv_positions"
   "DROP TABLE IF EXISTS npv"
   "DROP TABLE IF EXISTS subsubsubgenres"
   "DROP TABLE IF EXISTS subsubgenres"
   "DROP TABLE IF EXISTS subgenres"
   "DROP TABLE IF EXISTS genres"
   ))

;; The unit of computation is the file (or one user input). This means
;; that each file is processed in memory and only then commited to the
;; database. For the following reason we will need a helper SQL
;; function (upsert) to take care of the merging: (requires PostgreSQL>=9.1)
#_(defn create-functions
  []
  (sql/do-commands
   "CREATE OR REPLACE FUNCTION
upsert_lemmas( q_pos varchar(16), q_lemma varchar(32), q_genres_id INTEGER ) RETURNS void as $$
BEGIN
    UPDATE lemmas SET freq=freq+1 WHERE pos=q_pos AND lemma=q_lemma AND genres_id=q_genres_id;
    IF FOUND THEN
        RETURN;
    END IF;
    BEGIN
        INSERT INTO lemmas (pos, lemma, freq, genres_id) VALUES (q_pos, q_lemma, 1, q_genres_id);
    EXCEPTION WHEN OTHERS THEN
        UPDATE lemmas SET freq=freq+1 WHERE pos=q_pos AND lemma=q_lemma AND genres_id=q_genres_id;
    END;
    RETURN;
END;
$$ language plpgsql;"))

(defn invoke-with-connection
  [f]
  (sql/with-connection
    natsume-dbspec
    (sql/transaction
     (f))))

(def readability-fields-schema
  [[:length       "integer"]

   [:hiragana     "real"]
   [:katakana     "real"]
   [:kanji        "real"]
   [:romaji       "real"]
   [:symbols      "real"]
   [:commas       "real"]

   [:japanese     "real"]
   [:chinese      "real"]
   [:gairai       "real"]
   [:symbolic     "real"]
   [:mixed        "real"]
   [:unk          "real"]
   [:pn           "real"]

   [:obi2_level   "smallint"]
   [:tateishi     "real"]
   [:shibasaki    "real"]

   [:jlpt_level   "real"]
   [:bccwj_level  "real"]

   [:tokens       "real"]
   [:chunks       "real"]
   [:predicates   "real"]

   [:link_dist    "real"]
   [:chunk_depth  "real"]])

#_(defmacro create-table-vector
    [f name schema]
    `(apply ~f
            ~name
            ~@schema))

(defn create-tables-and-indexes
  "Create tables and indexes for Natsume.

  TODO benchmark w/o indexes (i.e. create indexes only after all data
  has been inserted; this will probably not apply to collocations as
  those require a lot of lookups(?))."
  []
  (do

    (sql/create-table
     "genres"
     [:id   "serial"      "PRIMARY KEY"]
     [:name "varchar(48)" "UNIQUE" "NOT NULL"])

    (sql/create-table
     "subgenres"
     [:id   "serial"      "PRIMARY KEY"]
     [:name "varchar(48)" "UNIQUE" "NOT NULL"])

    (sql/create-table
     "subsubgenres"
     [:id   "serial"      "PRIMARY KEY"]
     [:name "varchar(48)" "UNIQUE" "NOT NULL"])

    (sql/create-table
     "subsubsubgenres"
     [:id   "serial"      "PRIMARY KEY"]
     [:name "varchar(48)" "UNIQUE" "NOT NULL"])

    (sql/create-table
     "lemmas"
     [:id        "serial"      "PRIMARY KEY"]
     [:pos       "varchar(8)"  "NOT NULL"]
     [:name      "varchar(32)" "NOT NULL"]
     [:goshu     "varchar(4)"  "NOT NULL"])
    (sql/do-commands "CREATE INDEX idx_lemmas_pos  ON lemmas (pos)")
    (sql/do-commands "CREATE INDEX idx_lemmas_name ON lemmas (name)")

    (sql/create-table
     "orthbases"
     [:id        "serial"      "PRIMARY KEY"]
     [:lemmas_id "integer"     "NOT NULL" "REFERENCES lemmas(id)"]
     [:name      "varchar(32)" "NOT NULL"])
    (sql/do-commands "CREATE INDEX idx_orthbases_lemmas_id ON orthbases (lemmas_id)")
    (sql/do-commands "CREATE INDEX idx_orthbases_name      ON orthbases (name)")

    (sql/create-table
     "orthbases_genres_freqs"
     [:id           "serial"   "PRIMARY KEY"]
     [:orthbases_id "integer"  "NOT NULL" "REFERENCES orthbases(id)"]
     [:genres_id    "smallint" "NOT NULL" "REFERENCES genres(id)"]
     [:freq         "integer"  "NOT NULL"])
    (sql/do-commands "CREATE INDEX idx_orthbases_genres_freqs_orthbases_id ON orthbases_genres_freqs (orthbases_id)")
    (sql/do-commands "CREATE INDEX idx_orthbases_genres_freqs_genres_id    ON orthbases_genres_freqs (genres_id)")

    ;; TODO need to add information from CopyRight_Annotation.txt as
    ;; per BCCWJ usage guidelines.
    (sql/create-table
     "sources"
     [:id                 "serial"       "PRIMARY KEY"]
     [:title              "varchar(256)" "NOT NULL"]
     [:author             "varchar(256)"]
     [:year               "smallint"     "NOT NULL"]
     [:basename           "varchar(256)" "NOT NULL"]
     [:genres_id          "smallint"     "REFERENCES genres(id)"]
     [:subgenres_id       "smallint"     "REFERENCES subgenres(id)"]
     [:subsubgenres_id    "smallint"     "REFERENCES subsubgenres(id)"]
     [:subsubsubgenres_id "smallint"     "REFERENCES subsubsubgenres(id)"])
    (sql/do-commands "CREATE INDEX idx_sources_genres_id ON sources (genres_id)")
    (sql/do-commands "CREATE INDEX idx_sources_subgenres_id ON sources (subgenres_id)")
    (sql/do-commands "CREATE INDEX idx_sources_subsubgenres_id ON sources (subsubgenres_id)")
    (sql/do-commands "CREATE INDEX idx_sources_subsubsubgenres_id ON sources (subsubsubgenres_id)")

    (sql/create-table
     "sentences"
     [:id           "serial"   "PRIMARY KEY"]
     [:text         "text"     "NOT NULL"]
     ;; in the future, would it make sense to make a paragraphs table
     ;; that included readability based on paragraphs; also another
     ;; table for whole-file readability? (ie, the commas count is
     ;; already based on paragraph/whole file per-sentence averaging)
     [:paragraph_id "integer"  "NOT NULL"]
     [:sources_id   "integer"  "REFERENCES sources(id)"]
     ;; the following are raw numbers that are needed to calculate readability
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
     ;;[:obi2_level   "smallint"]
     ;;[:tateishi     "real"]
     ;;[:shibasaki    "real"]
     [:jlpt_level   "real"]
     [:bccwj_level  "real"]
     [:tokens       "smallint"]
     [:chunks       "smallint"]
     [:predicates   "smallint"]
     [:link_dist    "real"]
     [:chunk_depth  "real"])
    (sql/do-commands "CREATE INDEX idx_sentences_sources_id ON sentences (sources_id)")

    ;; The following are all average counts
    (apply sql/create-table
           "sentences_readability"
           (apply conj [[:sentences_id "integer" "PRIMARY KEY" "REFERENCES sentences(id)"]]
                  readability-fields-schema))

    (apply sql/create-table
           "paragraph_readability"
           (apply conj [[:paragraph_id "integer" "PRIMARY KEY" #_"REFERENCES sentences(paragraph_id)"]
                        [:sentences    "smallint"]]
                  readability-fields-schema))

    (apply sql/create-table
           "sources_readability"
           (apply conj [[:sources_id "integer" "PRIMARY KEY" "REFERENCES sources(id)"]
                        [:sentences  "smallint"]
                        [:paragraphs "smallint"]]
                  readability-fields-schema))

    (sql/create-table
     "npv"
     [:id       "serial"      "PRIMARY KEY"]
     [:noun     "varchar(48)" "NOT NULL"]
     [:particle "varchar(4)"  "NOT NULL"]
     [:verb     "varchar(48)" "NOT NULL"])
    (sql/do-commands
     "CREATE INDEX idx_npv_noun     ON npv (noun)"
     "CREATE INDEX idx_npv_particle ON npv (particle)"
     "CREATE INDEX idx_npv_verb     ON npv (verb)")

    (sql/create-table
     "npv_positions"
     [:id      "serial"   "PRIMARY KEY"]
     [:n_start "smallint" "NOT NULL"]
     [:n_end   "smallint" "NOT NULL"]
     [:p_start "smallint" "NOT NULL"]
     [:p_end   "smallint" "NOT NULL"]
     [:v_start "smallint" "NOT NULL"]
     [:v_end   "smallint" "NOT NULL"])

    (sql/create-table
     "npv_mappings"
     [:id               "serial"  "PRIMARY KEY"]
     [:npv_id           "integer" "REFERENCES npv(id)"]
     [:npv_positions_id "integer" "REFERENCES npv_positions(id)"]
     [:sentences_id     "integer" "REFERENCES sentences(id)"])
    (sql/do-commands
     "CREATE INDEX idx_npv_mappings_npv_id           ON npv_mappings (id)"
     "CREATE INDEX idx_npv_mappings_npv_positions_id ON npv_mappings (id)"
     "CREATE INDEX idx_npv_mappings_sentences_id     ON npv_mappings (id)")))

(declare sentences sources)

(defentity genres
  (entity-fields :id :name)
  (has-one sources))

(defentity subgenres
  (entity-fields :id :name)
  (has-one sources))

(defentity subsubgenres
  (entity-fields :id :name)
  (has-one sources))

(defentity subsubsubgenres
  (entity-fields :id :name)
  (has-one sources))

(declare orthbases)
(defentity lemmas
  (entity-fields :pos :name :goshu)
  (has-one orthbases))

(declare orthbases_genres_freqs)
(defentity orthbases
  (entity-fields :name)
  (belongs-to lemmas)
  (belongs-to orthbases_genres_freqs)
  (has-one orthbases_genres_freqs))

(defentity orthbases_genres_freqs
  (entity-fields :freq)
  (belongs-to orthbases)
  (belongs-to genres))

(defentity sources
  (entity-fields :id :title :author :year :basename :genres_id :subgenres_id :subsubgenres_id)
  (has-one sentences)
  (belongs-to genres)
  (belongs-to subgenres)
  (belongs-to subsubgenres)
  (belongs-to subsubsubgenres))

(def readability-keys
  (flatten (map first readability-fields-schema)))

(defn entity-fields-vector
  "Wrapper for entity-fields to make it work with vectors."
  [ent fields]
  (apply entity-fields ent fields))

(defentity sentences
  (entity-fields-vector (flatten
                         (list
                          :id :text :paragraph_id :sources_id
                          (filter
                           (fn [x] (not= x :tateishi :shibasaki))
                           readability-keys))))
  (belongs-to sources))

(defentity sentences_readability
  (entity-fields-vector (conj readability-keys :sentences_id))
  (belongs-to sentences))

(defentity paragraph_readability
  (entity-fields-vector (conj readability-keys
                              :paragraph_id
                              :sentences ))
  (has-one sentences {:fk :paragraph_id}))

(defentity sources_readability
  (entity-fields-vector (conj readability-keys
                              :sources_id
                              :sentences
                              :paragraphs))
  (belongs-to sources))

(defentity npv
  (entity-fields :noun :particle :verb))

(defentity npv_positions
  (entity-fields :n_start :n_end :p_start :p_end :v_start :v_end))

(defentity npv_mappings
  (has-one npv)
  (has-one sentences)
  (has-one npv_positions))

(defn init-database
  "Initialize Natsume database.
   Creates schema and indexes.
   Supports optional parameters:
   :destructive recreates all tables (destructive, use with caution!)"
  [params]
  (when (:destructive params) ;; Drop and recreate all tables
    (invoke-with-connection drop-all-indexes)
    (invoke-with-connection drop-all-tables))
  (do (invoke-with-connection create-tables-and-indexes)
      #_(invoke-with-connection create-functions)))

;; # Database query functions
;;
;; We can speed lookups by storing frequently checked data in memory,
;; at the expense of memory (be sure to leave out sentences and
;; infrequently accessed data).

(defn insert-if-not-exist
  "Inserts `vals` into entity `e` if vals do not already exist.
  Return the :id of the existing or inserted `vals`."
  [e vals]
  (let [results (select e (where vals))]
    (if (empty? results)
      (:id (insert e (values vals)))
      (get-in results [0 :id]))))

(def current-genres-id (atom 0))

(defn update-sources
  "Inserts sources meta-information from the corpus into the database.

  If a file is not present in `sources.tsv`, bail with a fatal error
  message; if a file is not in `sources.tsv` or not on the filesystem,
  then ignore that file (do not insert.)"
  [sources-metadata file-set]
  (log/debug (str "Updating sources with file-set " file-set))
  (doseq [[title author year basename genres-name subgenres-name
           subsubgenres-name subsubsubgenres-name permission] sources-metadata]
    (cond
     (not (contains? file-set basename))
     (log/debug (str "Skipping insertion of file " basename " in sources.tsv"))
     (not (empty? (select sources (where {:title basename}))))
     (log/debug (str "Skipping insertion of file " basename " because it is already in the database"))
     :else
     (let [genres-id          (insert-if-not-exist genres {:name genres-name})
           subgenres-id       (insert-if-not-exist subgenres {:name subgenres-name})
           subsubgenres-id    (insert-if-not-exist subsubgenres {:name subsubgenres-name})
           subsubsubgenres-id (insert-if-not-exist subsubsubgenres {:name subsubsubgenres-name})]
       (reset! current-genres-id genres-id)
       (log/trace (format "genres-id=%d\tsubgenres-id=%d\tsubsubgenres-id=%d\tsubsubsubgenres-id=%d"
                      genres-id subgenres-id subsubgenres-id subsubsubgenres-id))
       (insert sources
               (values {:title              title
                        :author             author
                        :year               (Integer/parseInt year)
                        :basename           basename
                        :genres_id          genres-id
                        :subgenres_id       subgenres-id
                        :subsubgenres_id    subsubgenres-id
                        :subsubsubgenres_id subsubsubgenres-id}))))))
;; (get-in (select genres (fields :id) (where {:name genres-name})) [0 :id])

(defn basename->source_id
  [basename]
  (get-in (select sources (fields :id) (where {:basename basename})) [0 :id]))

(defn insert-sentence
  [sentence-values f]
  (do (log/trace "Inserting sentence")
      (let [id (:id (insert sentences (values sentence-values)))
            readability-values (dissoc
                                (assoc sentence-values :sentences_id
                                       id :sentences 1)
                                :id :text :paragraph_id :sources_id)]
        (insert sentences_readability
                (values
                 (dissoc
                  (f readability-values (:text sentence-values))
                  :sentences)))))) ; unholy combination, REFACTOR

(defn last-paragraph-id
  []
  (let [r (:max-id (first (select sentences (aggregate (max :paragraph_id) :max-id))))]
    (if (nil? r) 0 r)))

(defn upsert-inc
  [pos1 lemma genres-id]
  (log/trace (format "upserting %s %s %d" pos1 lemma genres-id))
  (exec-raw (format "SELECT upsert_lemmas('%s', '%s', %d)" pos1 lemma genres-id) :results))

(defn write-pos-lemma-table
  [map]
  (doseq [[pos lemmas] map]
    (doseq [[lemma freq] lemmas]
      (insert lemmas (values {:pos pos :lemma lemma :freq freq})))))

;; Atom data structure: pos -> lemma -> freq.
;; Defined using defonce to prevent overwriting on recompilation.
(defonce inmemory-tokens
  (atom {}))

(defn inmemory-token-inc!
  [pos1 pos2 goshu lemma orthbase]
  (log/debug (format "%s %s %s %s %s" pos1 pos2 goshu lemma orthbase))
  (swap! inmemory-tokens update-in
         [[(str pos1 pos2) goshu] lemma orthbase]
         (fn [freq] (if (nil? freq) 1 (inc freq)))))

(defn reset-inmemory-tokens!
  []
  (reset! inmemory-tokens {}))

;;

(defn merge-tokens!
  "genres-id:pos -> lemma: freq"
  []
  #_(insert lemmas (values {:pos pos :goshu goshu :name lemma}))
  (doseq [[[pos goshu] lemmas] @inmemory-tokens
          [lemma orthbases]    lemmas]
    (let [lemma-id (get-in (exec-raw ["INSERT INTO lemmas (pos, goshu, name) VALUES (?, ?, ?) RETURNING id" [pos goshu lemma]] :results) [0 :id])]
      (log/debug (format "lemma-id: %d" lemma-id))
      (doseq [[orthbase freq]    orthbases]
        (let [orthbase-id (get-in (exec-raw ["INSERT INTO orthbases (lemmas_id, name) VALUES (?, ?) RETURNING id" [lemma-id orthbase]] :results) [0 :id])
              #_(:id (insert orthbases (values [{:name orthbase :lemmas_id lemma-id}])))]
          (exec-raw ["INSERT INTO orthbases_genres_freqs (orthbases_id, genres_id, freq) VALUES (?, ?, ?)" [orthbase-id @current-genres-id freq]])
          #_(insert orthbases_genres_freqs (values {:orthbases_id orthbase-id :genres_id @current-genres-id :freq freq}))))))
  #_(let [tokens-parts (partition-all
                      1000 ; if we don't partiton the insert fails because of length issues
                      (mapv #(do (log/debug %) (assoc-in % [1 :genres_id] @current-genres-id)) ; FIXME/TODO
                            (for [[[pos goshu] lemmas] @inmemory-tokens
                                  [lemma orthbases]    lemmas
                                  [orthbase freq]      orthbases]
                              (do (log/debug (format "Making.. '%s %s %s' :: '%s %s'" pos goshu lemma orthbase freq))
                                  [{:pos pos :goshu goshu :name lemma}
                                   {:name orthbase :freq freq}]))))]
    (log/debug (format "tokens-part: '%s'" (vector tokens-parts)))
    (doseq [part tokens-parts
            [lemmas-part orthbase-part] part]
      (log/debug (format "lemmas-part: '%s'" lemmas-part))
      (log/debug (format "orthbase-part: '%s'" orthbase-part))
      (let [lemmas-id (insert lemmas
                              (values lemmas-part))]
        (log/debug (format "Lemmas id: %s" lemmas-id))
        (insert orthbases
                (values orthbase-part))
        (insert orthbases_genres_freqs
                (values orthbase-part))))))

(defn get-genres
  []
  (select genres))

(defn get-genre-token-counts
  []
  (let [r (select orthbases_genres_freqs
                  (fields :genres_id)
                  (aggregate (sum :freq) :total :genres_id))]
    (into {} (for [row r] [(:genres_id row) (:total row)]))))

(defn get-token-freqs
  [pos orthbase]
  (select orthbases
          (with lemmas)
          (with orthbases_genres_freqs)
          (fields :orthbases_genres_freqs.freq :orthbases_genres_freqs.genres_id)
          (where {:name orthbase :lemmas.pos pos})
          (group :orthbases_genres_freqs.genres_id :orthbases.name :orthbases_genres_freqs.freq)))

(defn get-norm-token-freqs
  [pos orthbase]
  (let [freqs (get-token-freqs pos orthbase)
        genre-totals (get-genre-token-counts)]
    (into {}
          (map (fn [m]
                 (let [genre (:genres_id m)
                       freq  (:freq m)]
                   [genre (/ freq (get genre-totals genre))]))
               freqs))))

(defn register-score
  [pos orthbase good-id bad-id]
  (let [r (get-norm-token-freqs pos orthbase)]
    (- (Math/log (get r good-id 1)) (Math/log (get r bad-id 1)))))

(def sum-statement
  "sum(length) AS length, sum(hiragana) AS hiragana,
sum(katakana) AS katakana, sum(kanji) AS kanji, sum(romaji) AS romaji,
sum(commas) AS commas, sum(symbols) AS symbols, sum(japanese) AS japanese,
sum(chinese) AS chinese, sum(gairai) AS gairai, sum(symbolic) AS symbolic,
sum(mixed) AS mixed, sum(unk) AS unk, sum(pn) AS pn, sum(tokens) AS tokens,
sum(chunks) AS chunks, sum(predicates) AS predicates,
sum(jlpt_level) AS jlpt_level, sum(bccwj_level) AS bccwj_level,
sum(link_dist) AS link_dist, sum(chunk_depth) AS chunk_depth")

(defn paragraph-readability-sums
  [id]
  (first (exec-raw [(str "SELECT count(id) AS sentences, "
                         sum-statement
                         " FROM sentences WHERE paragraph_id=?")
                    [id]]
                   :results)))

(defn sources-readability-sums
  [id]
  (first (exec-raw
          [(str "SELECT count(id) AS sentences, count(DISTINCT paragraph_id) AS paragraphs, "
                sum-statement
                " FROM sentences WHERE sources_id=?")
           [id]]
          :results)))

(defn get-paragraph-text
  [id]
  (:string_agg (first (exec-raw
                       ["SELECT string_agg(text, '\n') FROM sentences WHERE paragraph_id=?"
                        [id]]
                       :results))))

(defn get-sources-text
  [id]
  (:string_agg (first (exec-raw
                       ["SELECT string_agg(text, '\n') FROM sentences WHERE sources_id=?"
                        [id]]
                       :results))))

(defn insert-paragraph-readability
  [id f]
  (insert paragraph_readability
          (values (assoc
                      (f (paragraph-readability-sums id) (get-paragraph-text id))
                    :paragraph_id id))))

(defn insert-sources-readability
  [id f]
  (insert sources_readability
          (values
           (assoc
               (f (sources-readability-sums id) (get-sources-text id))
             :sources_id id))))

;; # TODO
;;
;; - investigate lobos: http://www.vijaykiran.com/2012/01/17/web-application-development-with-clojure-part-2/
;; - read before deploying: http://www.depesz.com/2012/06/10/why-is-upsert-so-complicated/
;; - http://clojureelasticsearch.info/articles/facets.html <-- faceted fulltext search, look into how it works with postgres
;; - Clojure trie implementation: https://github.com/reverendpaco/clojure-dictionary-trie/blob/master/src/dictionary_trie/trie.clj
;; - use (declare ...) to pre-declare entites
;; - `korma.incubator` - try to get many-to-many relationships working
