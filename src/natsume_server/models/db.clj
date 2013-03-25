(ns natsume-server.models.db
  (:require [korma.db :refer :all]
            [korma.core :refer :all]
            [korma.config :as korma-config]
            [clojure.java.jdbc :as sql]

            [clojure.string :as string]
            [plumbing.core :refer [?> ?>> map-keys]]

            [natsume-server.models.schema :as schema]))

(defdb db schema/dbspec)

(korma-config/set-naming
 {:keys schema/underscores->dashes
  :fields schema/dashes->underscores})

;; Entity declarations

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

(defentity sources
  (entity-fields :id :title :author :year :basename :genres-id :subgenres-id :subsubgenres-id)
  (has-one sentences)
  (belongs-to genres)
  (belongs-to subgenres)
  (belongs-to subsubgenres)
  (belongs-to subsubsubgenres))

(defentity sentences
  (entity-fields :id :text :sentence-order-id :paragraph-id :sources-id :type
                 :length :hiragana :katakana :kanji :romaji :symbols :commas
                 :japanese :chinese :gairai :symbolic :mixed :unk :pn :obi2-level
                 :jlpt-level :bccwj-level :tokens :chunks :predicates :link-dist
                 :chunk-depth)
  (belongs-to sources))

(defentity tokens
  (entity-fields :pos1 :pos2 :orthBase :lemma)
  (belongs-to sentences))

(defentity gram-2
  (entity-fields :sentences-id
                 :string-1 :pos-1 :tags-1 :begin-1 :end-1
                 :string-2 :pos-2 :tags-2 :begin-2 :end-2)
  (belongs-to sentences))
(defentity gram-3
  (entity-fields :sentences-id
                 :string-1 :pos-1 :tags-1 :begin-1 :end-1
                 :string-2 :pos-2 :tags-2 :begin-2 :end-2
                 :string-3 :pos-3 :tags-3 :begin-3 :end-3)
  (belongs-to sentences))
(defentity gram-4
  (entity-fields :sentences-id
                 :string-1 :pos-1 :tags-1 :begin-1 :end-1
                 :string-2 :pos-2 :tags-2 :begin-2 :end-2
                 :string-3 :pos-3 :tags-3 :begin-3 :end-3
                 :string-4 :pos-4 :tags-4 :begin-4 :end-4)
  (belongs-to sentences))

;; ## Insertion functions

;; ### Genres
(defn- named-vals->map [xs]
  (into {} (map (comp vec vals) xs)))

(defn update-genres! [] ; FIXME -- rewire into init function when not destructive
  (reset! schema/!genres
          {:genres          (named-vals->map (select genres))
           :subgenres       (named-vals->map (select subgenres))
           :subsubgenres    (named-vals->map (select subsubgenres))
           :subsubsubgenres (named-vals->map (select subsubsubgenres))}))

;; ### Sources
(defn- insert-if-not-exist
  "Inserts `vals` into entity `e` if vals do not already exist.
  Return the :id of the existing or inserted `vals`."
  [e genre-level genre-name]
  (if-let [id (get-in @schema/!genres [genre-level genre-name])]
    id
    (let [id* (:id (insert e (values {:name genre-name})))]
      (swap! schema/!genres assoc-in [genre-level genre-name] id*)
      id*)))

(defn- make-sources-record [v]
  (let [[title author year basename genres-name subgenres-name
         subsubgenres-name subsubsubgenres-name permission] v
         genres-id          (insert-if-not-exist genres :genres genres-name)
         subgenres-id       (insert-if-not-exist subgenres :subgenres subgenres-name)
         subsubgenres-id    (insert-if-not-exist subsubgenres :subsubgenres subsubgenres-name)
         subsubsubgenres-id (insert-if-not-exist subsubsubgenres :subsubsubgenres subsubsubgenres-name)]
    {:title              title
     :author             author
     :year               (Integer/parseInt year)
     :basename           basename
     :genres-id          genres-id
     :subgenres-id       subgenres-id
     :subsubgenres-id    subsubgenres-id
     :subsubsubgenres-id subsubsubgenres-id}))

(defn update-sources!
  "Inserts sources meta-information from the corpus into the database.

  If a file is not present in `sources.tsv`, bail with a fatal error (FIXME)
  message; if a file is not in `sources.tsv` or not on the filesystem,
  then ignore that file (do not insert.)"
  [sources-metadata file-set]
  ;; Try to guess the genre id by name, checking to see if it is
  ;; already in the database (not checking in the !genres atom as that
  ;; is not persistent).
  (let [genres-name        (-> sources-metadata first (nth 4))
        genres-id          (:id (select genres (where {:name genres-name})))
        existing-basenames (set (map :basename (select sources (where {:genres-id genres-id}) (fields :basename))))]
    (->> (partition-all 1000 sources-metadata)
         (map #(filter (fn [record] (contains? file-set (nth record 3))) %))
         ;; Optionally filter out sources already in database.
         (?>> (not-empty existing-basenames) map #(filter (fn [record] (not (contains? existing-basenames (nth record 3)))) %))
         (map #(map make-sources-record %))
         ((comp dorun map) #(if (seq %) (insert sources (values %)))))))

;; ### Sentence

(defn insert-sentence [sentence-values]
  (insert sentences (values (select-keys sentence-values
                                         [:unk :text :kanji :mixed :romaji :chunk-depth :japanese :length :symbols :sentence-order-id :paragraph-id :jlpt-level :commas :symbolic :gairai :chinese :predicates :sources-id :katakana :bccwj-level :chunks :hiragana :tokens :link-dist :pn]))))

;; ### Collocations
(defn- make-jdbc-array [xs]
  (let [conn (sql/find-connection)]
    (.createArrayOf conn "text" (into-array String xs))))
(defn insert-collocations! [collocations sentences-id]
  (schema/with-dbmacro
    (doseq [collocation (filter #(> (count (:type %)) 1) collocations)]
      (let [grams (count (:type collocation))
            record-map (apply merge (for [i (range 1 (inc grams))]
                                      (let [record (nth (:data collocation) (dec i))]
                                        (map-keys #(let [[f s] (string/split (name %) #"-")]
                                                     (keyword (str s "-" i)))
                                                  (-> record
                                                      (?> (:head-pos record) update-in [:head-pos] name)
                                                      (?> (:tail-pos record) update-in [:tail-pos] name)
                                                      (?> (:head-tags record) update-in [:head-tags] #(make-jdbc-array (map name %)))
                                                      (?> (:tail-tags record) update-in [:tail-tags] #(make-jdbc-array (map name %))))))))]
        ;; FIXME dynamic symbol mapping (maybe add current namespace?)
        (case grams
          2 (insert gram-2 (values (assoc record-map :sentences-id sentences-id)))
          3 (insert gram-3 (values (assoc record-map :sentences-id sentences-id)))
          4 (insert gram-4 (values (assoc record-map :sentences-id sentences-id))))))))

;; ### Tokens
(defn insert-tokens! [token-seq sentences-id]
  (insert tokens (values (mapv #(assoc (select-keys % [:pos1 :pos2 :orthBase :lemma])
                                  :sentences-id sentences-id)
                               token-seq))))

;; ## Query functions

(defn basename->source-id
  [basename]
  (get-in (select sources (fields :id) (where {:basename basename})) [0 :id]))

(defn sources-id->genres-id [sources-id]
  (get-in (select sources (fields :genres-id) (where {:id sources-id})) [0 :genres-id]))

(defn get-genres []
  (select genres))

(defn get-progress []
  (exec-raw ["select genres.name, genres.id, count(DISTINCT sources.id) as done, (select count(DISTINCT so.id) from sources as so, genres as ge where so.id NOT IN (select DISTINCT sources_id from sentences) and ge.id=genres.id and ge.id=so.genres_id) as ongoing from sources, sentences, genres where sources.id=sentences.sources_id and genres.id=sources.genres_id group by genres.id order by genres.id"] :results))

(defn- get-genre-token-counts
  []
  (let [r (select orthbases-genres-freqs
                  (fields :genres-id)
                  (aggregate (sum :freq) :total :genres-id))]
    (into {} (for [row r] [(:genres-id row) (:total row)]))))
(defn- get-token-freqs
  [pos orthbase]
  (select orthbases
          (with lemmas)
          (with orthbases-genres-freqs)
          (fields :orthbases-genres-freqs.freq :orthbases-genres-freqs.genres-id)
          (where {:name orthbase :lemmas.pos pos})
          (group :orthbases-genres-freqs.genres-id :orthbases.name :orthbases-genres-freqs.freq)))
(defn- get-norm-token-freqs
  [pos orthbase]
  (let [freqs (get-token-freqs pos orthbase)
        genre-totals (get-genre-token-counts)]
    (into {}
          (map (fn [m]
                 (let [genre (:genres-id m)
                       freq  (:freq m)]
                   [genre (/ freq (get genre-totals genre))]))
               freqs))))
(defn register-score
  "TODO incanter chisq-test http://incanter.org/docs/api/#member-incanter.stats-chisq-test
   TODO make the method selectable from the api, ie. difference, chi-sq, etc...
   TODO for tokens, register score must take into account the lemma, to find which orthBases occurr in which corpora -> possible to filter what orthBases are register-specific, what are generally agreed upon"
  [pos orthbase good-id bad-id]
  (let [r (get-norm-token-freqs pos orthbase)]
    (- (Math/log (get r good-id 1)) (Math/log (get r bad-id 1)))))
