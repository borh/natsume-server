(ns natsume-server.models.db
  (:require [korma.db :refer :all]
            [korma.core :refer :all]
            [korma.config :as korma-config]
            [clojure.java.jdbc :as sql]

            [clojure.string :as string]
            [clojure.zip :as z]
            [clojure.walk :as walk]
            [plumbing.core :refer [?> ?>> map-keys map-vals]]

            [natsume-server.models.schema :as schema]
            [natsume-server.utils.naming :refer [dashes->underscores underscores->dashes]])
  (:import [org.postgresql.util PGobject]))

(defdb db schema/dbspec)

(korma-config/set-naming
 {:keys underscores->dashes
  :fields dashes->underscores})

;; Entity declarations

(declare sentences)

(defn- make-jdbc-array [xs]
  (let [conn (sql/find-connection)]
    (.createArrayOf conn "text" (into-array String xs))))

(defn- seq->ltree
  "Converts a sequence into a PostgreSQL ltree object."
  [fields]
  (doto (PGobject.)
    (.setType "ltree")
    (.setValue (string/join "."
                            (->> fields
                                 (reduce #(if (not= (peek %1) %2) (conj %1 %2) %1) [])
                                 (map #(string/replace % #"(\p{P}|\s)+" "_")))))))
(defn- ltree->seq
  "Converts a PostgreSQL ltree object into a sequence."
  [^PGobject pgobj]
  (-> pgobj
      .toString
      (string/split #"\.")))

(defentity sources
  (entity-fields :id :title :author :year :basename :genre)
  (prepare #(-> % (?> (:genre %) update-in [:genre] seq->ltree)))
  (transform #(-> % (?> (:genre %) update-in [:genre] ltree->seq)))
  (has-one sentences))

(defentity sentences
  (entity-fields :id :text :sentence-order-id :paragraph-order-id :sources-id :tags
                 :length :hiragana :katakana :kanji :romaji :symbols :commas
                 :japanese :chinese :gairai :symbolic :mixed :unk :pn :obi2-level
                 :jlpt-level :bccwj-level :tokens :chunks :predicates :link-dist
                 :chunk-depth)
  ;; The following transform is included because Korma does not pick up on the sources genre transform in `with` queries.
  (transform #(-> % (?> (:genre %) update-in [:genre] ltree->seq)))
  (belongs-to sources))

(defentity tokens
  (entity-fields :pos1 :pos2 :orthBase :lemma)
  (belongs-to sentences))
(defentity search-tokens
  (entity-fields :pos1 :pos2 :orthBase :lemma :genre :count)
  (prepare #(-> % (?> (:genre %) update-in [:genre] seq->ltree)))
  (transform #(-> % (?> (:genre %) update-in [:genre] ltree->seq))))
(defentity genre-norm
  (entity-fields :genre :token-count :chunk-count :sentences-count :paragraphs-count :sources-count)
  (prepare #(-> % (?> (:genre %) update-in [:genre] seq->ltree)))
  (transform #(-> % (?> (:genre %) update-in [:genre] ltree->seq))))

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

(defentity search-gram-3
  (entity-fields :type :string-1 :string-2 :string-3 :genre
                 :count :sentences-count :paragraphs-count :sources-count))

(defentity gram-norm
  (entity-fields :type :genre :count :sentences-count #_:paragraphs-count :sources-count)
  (prepare #(-> %
                (?> (:genre %) update-in [:genre] seq->ltree)
                (?> (:type %) update-in [:type] dashes->underscores)))
  (transform #(-> %
                  (?> (:genre %) update-in [:genre] ltree->seq)
                  (?> (:type %) update-in [:type] underscores->dashes))))

;; ## Insertion functions

;; ### Sources

(defn- make-sources-record [v]
  (let [[title author year basename genres-name subgenres-name
         subsubgenres-name subsubsubgenres-name permission] v]
    {:title    title
     :author   author
     :year     (Integer/parseInt year)
     :basename basename
     :genre    [genres-name subgenres-name subsubgenres-name subsubsubgenres-name]}))

(defn update-source! [sources-metadata]
  (insert sources (values sources-metadata)))

(defn update-sources!
  "Inserts sources meta-information from the corpus into the database.

  If a file is not present in `sources.tsv`, bail with a fatal error (FIXME)
  message; if a file is not in `sources.tsv` or not on the filesystem,
  then ignore that file (do not insert.)"
  [sources-metadata file-set]
  (let [existing-basenames (set (map :basename (select sources (where {:genre (seq->ltree (vector (nth (first sources-metadata) 4) "*"))}) (fields :basename))))]
    (->> (partition-all 1000 sources-metadata)
         (map #(filter (fn [record] (contains? file-set (nth record 3))) %))
         ;; Optionally filter out sources already in database.
         (?>> (not-empty existing-basenames) map #(filter (fn [record] (not (contains? existing-basenames (nth record 3)))) %))
         (map #(map make-sources-record %))
         ((comp dorun map) #(if (seq %) (insert sources (values %)))))))

;; ### Sentence

(defn insert-sentence [sentence-values]
  (schema/with-dbmacro ; Needed to keep connection open for make-jdbc-array to do its thing.
   (insert sentences (values (-> sentence-values
                                 (update-in [:tags] #(make-jdbc-array (map name %)))
                                 (select-keys
                                  [:unk :tags :text :kanji :mixed :romaji :chunk-depth :japanese :length :symbols :sentence-order-id :paragraph-order-id :jlpt-level :commas :symbolic :gairai :chinese :predicates :sources-id :katakana :bccwj-level :chunks :hiragana :tokens :link-dist :pn]))))))

;; ### Collocations
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
  (-> (select sources (fields :id) (where {:basename basename}))
      first
      :id))

(defn get-progress []
  (exec-raw ["select genres.name, genres.id, count(DISTINCT sources.id) as done, (select count(DISTINCT so.id) from sources as so, genres as ge where so.id NOT IN (select DISTINCT sources_id from sentences) and ge.id=genres.id and ge.id=so.genres_id) as ongoing from sources, sentences, genres where sources.id=sentences.sources_id and genres.id=sources.genres_id group by genres.id order by genres.id"] :results))

;; ### Genre tree creation
;;
;; Output format follows the d3 tree example: https://github.com/mbostock/d3/wiki/Tree-Layout
;;
;; ```json
;; {
;;  "name": "flare",
;;  "children": [
;;   {
;;    "name": "analytics",
;;    "children": [
;;     {
;;      "name": "cluster",
;;      "children": [
;;       {"name": "AgglomerativeCluster", "size": 3938},
;;       {"name": "CommunityStructure", "size": 3812},
;;       {"name": "MergeEdge", "size": 743}
;;      ]
;;     },
;;     {
;;      "name": "graph",
;;      "children": [
;;       {"name": "BetweennessCentrality", "size": 3534},
;;       {"name": "LinkDistance", "size": 5731}
;;      ]
;;     }
;;    ]
;;   }
;;  ]
;; }
;; ```

(defn- tree-zipper [root]
  (letfn [(make-node [node children]
            (with-meta (assoc node :children (vec children)) (meta node)))]
    (z/zipper map? :children make-node root)))

(defn- root-loc
  "zips all the way up and returns the root loc, reflecting any
  changes. Modified from clojure.zip/root"
  [loc]
  (if (= :end (loc 1))
    loc
    (let [p (z/up loc)]
      (if p
        (recur p)
        loc))))
(defn- find-index [field-name root]
  (if-let [children (:children root)]
    (if-let [[index _]
             (->> children ; Linear search (but n is small).
                  (map-indexed vector)
                  (some #(and (= (:name (second %)) field-name) %)))]
      index)))
(defn- find-loc [field-name root]
  (loop [loc root]
    (if loc
      (if (= field-name (-> loc z/node :name))
        loc
        (recur (z/right loc))))))

(defn seq-to-tree
  "Transforms a vector of hierarchies (just another vector) into a tree data structure suitable for export to JavaScript.

  The underlying algorithm utilizes a custom tree zipper function.
  One downside to using zippers here is that searching for the child nodes is linear, but since the tree is heavily branched, this should not pose a problem even with considerable data.
  TODO: make node properties parameters, sentence-level features Q/A, conversational, etc?"
  [hierarchies & {:keys [merge-fn root-name root-value]
                  :or {merge-fn '+ root-name "Genres"}}] ; -> [[:a :b :c :d] [:a :b :x :z] ... ]
  (z/root ; Return final tree.
   (reduce ; Reduce over hierarchies.
    (fn [tree {:keys [count genre]}]
      (->> genre
           (reduce ; Reduce over fields in hierarchy (:a, :b, ...).
            (fn [loc field]
              (if-let [found-loc (if-let [child-loc (z/down loc)] (find-loc field child-loc))]
                (z/edit found-loc update-in [:count] merge-fn count) ; Node already exists: update counts in node.
                (-> loc ; Add new node and move loc to it (insert-child is a prepend op).
                    (z/insert-child {:name field :count count})
                    z/down)))
            tree)
           root-loc)) ; Move to root location.
    (tree-zipper {:name root-name :count (or root-value (reduce merge-fn (map :count hierarchies)))})
    hierarchies)))

(defn- tree-path [tree-loc]
  (loop [path '()
         loc tree-loc]
    (if loc
      (recur
       (conj path (-> loc z/node :name))
       (z/up loc))
      path)))

(defn- get-count-in-tree [field loc ks]
  (loop [node-names ks
         t-loc loc
         count 0]
    (if-not node-names ; synthread?
      count
      (if t-loc
        (if-let [found-loc (find-loc (first node-names) t-loc)]
          (recur (next node-names)
                 (z/down found-loc)
                 (-> found-loc z/node field int)))))))

(defn normalize-tree
  "Given a genre tree in-tree, returns normalized counts by genre, which are passed in as norm-tree.
  The type of normalized counts returned depend on in-tree, while norm-tree can use a number of counts: document, paragraph and sentence."
  [norm-tree in-tree & {:keys [update-field boost-factor]
                        :or {update-field :count boost-factor 1000000}}]
  (let [in-tree-zipper (tree-zipper in-tree)
        norm-tree-zipper (tree-zipper norm-tree)]
    (loop [loc in-tree-zipper]
      (if (z/end? loc)
        (z/root loc)
        (recur
         (z/next ; NOTE: get-count-in-tree should always return a positive number.
          (z/edit loc update-in [update-field] #(/ % (get-count-in-tree update-field norm-tree-zipper (tree-path loc)) (/ 1 boost-factor)))))))))

(defn get-genres []
  (distinct (map :genre (select sources (fields :genre) (order :genre)))))

(defn get-genre-counts []
  (select sources (fields :genre) (aggregate (count :*) :count :genre)))

(defn genres->tree []
  (-> (get-genre-counts)
      seq-to-tree))

(defn sources-id->genres-map [sources-id]
  (->> (select sources (fields :genre) (where {:id sources-id}))
       (map :genre)
       distinct))

(defn sentences-by-genre [q]
  (let [query (string/join "." (if (< (count q) 4) (conj q "*") q))]
    (println query)
    (map :text (exec-raw [(str "SELECT text FROM sentences, sources WHERE sentences.sources_id=sources.id AND sources.genre ~ '"
                               query "'")]
                         :results))))

(defn all-sentences-with-genre []
  (select sentences (with sources) (fields :text :sources.genre) (group :sources.genre :sentences.id)))

(defn sources-ids-by-genre [genre-vec]
  (map :id (select sources (fields :id) (where {:genre (seq->ltree genre-vec)}))))

(defn sources-text [id]
  (apply str (map :text (select sentences (fields :text) (with sources) (where {:sources-id :sources.id :sources.id id})))))

(defn sources-tokens [id]
  (into-array String (map :lemma (select tokens (fields :lemma) (with sentences) (join sources (= :sources.id :sentences.sources_id)) (where {:sentences.id :sentences_id :sources.id id})))))

(try ; Don't error out when table not yet created.
 (def norm-map ; TODO: better way of handling this?
   {:sources    (seq-to-tree (select genre-norm (fields :genre [:sources-count :count])))
    :paragraphs (seq-to-tree (select genre-norm (fields :genre [:paragraphs-count :count])))
    :sentences  (seq-to-tree (select genre-norm (fields :genre [:sentences-count :count])))
    :chunks     (seq-to-tree (select genre-norm (fields :genre [:chunk-count :count])))
    :tokens     (seq-to-tree (select genre-norm (fields :genre [:token-count :count])))})
 (catch Exception e))

(defn get-search-tokens [query-map opts]
  (let [records (select search-tokens (where (select-keys query-map [:lemma :orthbase :pos1 :pos2])))]
    (->> records
         (group-by #(select-keys % [:lemma :orthbase :pos1 :pos2]))
         (map-vals seq-to-tree)
         ;; Optionally normalize results if :norm key is set and available.
         (?>> (contains? norm-map (:norm opts)) map-vals (partial normalize-tree ((:norm opts) norm-map)))
         ;; Below is for API/JSON TODO (might want to move below to service.clj) as it is more JSON/d3-specific
         vec
         (map #(hash-map :token (first %) :results (second %))))))

(comment ; If we want to search before making search tables --- not such a good idea.
  (defn get-tokens [query-map]
    (let [records (select tokens
                          (fields :lemma :orthbase :pos1 :pos2 :sources.genre)
                          (join sentences (= :sentences_id :sentences.id))
                          (join sources (= :sources.id :sentences.sources_id))
                          (where query-map)
                          (group :lemma :orthbase :pos1 :pos2)
                          (aggregate (count :*) :count :sources.genre))]
      (->> records
           (map #(update-in % [:genre] ltree->seq))
           (group-by #(select-keys % [:lemma :orthbase :pos1 :pos2]))
           (map-vals seq-to-tree)
           ;; Below is for API/JSON TODO might want to move below to service.clj as it is more JSON/d3-specific.
           vec
           (map #(hash-map :token (first %) :results (second %)))))))

(defn get-gram-counts [m]
  (apply + (map :count (select search-gram-3 (fields :count) (where (update-in m [:type] dashes->underscores))))))

(def get-gram-totals
  (memoize #(apply + (map :count (select gram-norm (fields :count) (where (update-in % [:type] dashes->underscores)))))))
