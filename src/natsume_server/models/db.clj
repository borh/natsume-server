(ns natsume-server.models.db
  (:require [honeysql.core :as h]
            [honeysql.format :as fmt]
            [honeysql.helpers :refer :all]
            [d3-compat-tree.tree :refer :all]
            [natsume-server.models.sql-helpers :refer :all]
            [natsume-server.models.schema :as schema]

            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.core.reducers :as r]
            [clojure.tools.reader.edn :as edn]
            [plumbing.core :refer [?> ?>> map-keys map-vals for-map]]

            [natsume-server.stats :as stats]
            [natsume-server.utils.naming :refer [dashes->underscores underscores->dashes]]))

;; ## Insertion functions

;; ### Sources

(defn insert-source! [sources-metadata]
  (i! :sources (update-in sources-metadata [:genre] seq->ltree)))

(defn insert-sources!
  "Inserts sources meta-information from the corpus into the database.

  If a file is not present in `sources.tsv`, bail with a fatal error (FIXME)
  message; if a file is not in `sources.tsv` or not on the filesystem,
  then ignore that file (do not insert.)"
  [sources-metadata file-set]
  (->> sources-metadata
       (filter (fn [{:keys [basename]}] (contains? file-set basename)))
       ;; Optionally filter out sources already in database.
       ;;(?>> (not-empty existing-basenames) (map #(filter (fn [record] (not (contains? existing-basenames (nth record 3)))) %)))
       ((comp dorun map) insert-source!)
       #_((comp dorun map) #(if (seq %) ((comp dorun map) insert-source! %)))))

;; ### Sentence

(defn insert-sentence [sentence-values]
  (i! :sentences
      (-> sentence-values
          #_(update-in [:s] make-jdbc-array)
          (select-keys (schema/schema-keys schema/sentences-schema)))))

;; ### Collocations
(defn insert-collocations! [collocations sentences-id]
  (doseq [collocation (filter identity #_#(> (count (:type %)) 1) collocations)]
    (let [grams (count (:type collocation))
          record-map (apply merge (for [i (range 1 (inc grams))]
                                    (let [record (nth (:data collocation) (dec i))]
                                      (map-keys #(let [[f s] (string/split (name %) #"-")]
                                                   (keyword (str s "-" i)))
                                                (-> record
                                                    (?> (:head-pos record) (update-in [:head-pos] name))
                                                    (?> (:tail-pos record) (update-in [:tail-pos] name))
                                                    #_(?> (:head-tags record) (update-in [:head-tags] make-jdbc-array))
                                                    #_(?> (:tail-tags record) (update-in [:tail-tags] make-jdbc-array)))))))]
      (i! (keyword (str "gram-" grams))
          (assoc record-map :sentences-id sentences-id)))))

;; ### Tokens
(defn insert-tokens! [token-seq sentences-id]
  (i! :tokens (->> token-seq
                   (map #(assoc (select-keys % [:pos-1 :pos-2 :pos-3 :pos-4 :c-type :c-form :lemma :orth :pron :orth-base :pron-base :goshu])
                           :sentences-id sentences-id)))))

;; ## Query functions

(defn basename->source-id
  [basename]
  (-> (q (-> (select :id)
             (from :sources)
             (where [:= :basename basename])))
      first
      :id))

(defn get-genres []
  (distinct (map :genre (q (-> (select :genre)
                               (from :sources)
                               (order-by :genre))))))

(defn get-genre-counts []
  (q {:select [:genre [(h/call :count :*) :count]]
      :from [:sources]
      :group-by [:genre]}))

(defn genres->tree []
  (-> (get-genre-counts)
      seq-to-tree))

(defn sources-id->genres-map [sources-id]
  (->> (q {:select [:genre]
           :from [:sources]
           :where [:= :id sources-id]})
       (map :genre)
       distinct))

(defn sentences-by-genre [genre]
  (let [query (if (< (count genre) 4) (conj genre "*") genre)]
    (println query)
    (map :text
         (q (-> (select :text)
                (from :sentences :sources)
                (where [:and
                        [:= :sentences.sources_id :sources.id]
                        [:tilda :sources.genre query]]))))))

(defn tokens-by-genre [genre & {:keys [field] :or {field :lemma}}]
  (let [query (if (< (count genre) 4) (conj genre "*") genre)]
    (println query)
    (mapcat vals
            (q #_(h/raw (str "SELECT string_agg(tokens.orth, ' ') FROM tokens, sentences, sources WHERE tokens.sentences_id=sentences.id AND sentences.sources_id=sources.id AND sources.genre ~ '" query "' GROUP BY tokens.sentences_id"))
               (-> (select (h/raw (str "string_agg(tokens." (name field) ", ' ')")))
                   (from :tokens :sentences :sources)
                   (where [:and
                           [:= :tokens.sentences-id :sentences.id]
                           [:= :sentences.sources-id :sources.id]
                           [:tilda :sources.genre genre]])
                   (group :tokens.sentences-id))))))


(defn all-sentences-with-genre []
  (q (-> (select :text :sources.genre)
         (from :sentences :sources)
         (where [:= :sentences.sources_id :sources.id])
         (group :sources.genre :sentences.id))))

(defn sources-ids-by-genre [genre]
  (let [query (if (< (count genre) 4) (conj genre "*") genre)]
    (map :id
         (q (-> (select :id)
                (from :sources)
                (where [:tilda :genre query]))))))

(defn sources-text [id]
  (map :text
       (q (-> (select :text)
              (from :sentences :sources)
              (where [:and
                      [:= :sources.id id]
                      [:= :sentences.sources_id :sources.id]])))))

(defn sources-tokens [id & {:keys [field] :or {field :lemma}}]
  (mapcat vals
          (q (-> (select (h/raw (str "string_agg(tokens." (name field) ", ' ')")))
                 (from :tokens :sentences :sources)
                 (where [:and
                         [:= :sources.id id]
                         [:= :tokens.sentences-id :sentences.id]
                         [:= :sentences.sources_id :sources.id]])
                 (group :tokens.sentences-id)))))

(def norm-map
  (delay
   {:sources    (seq-to-tree (q (-> (select :genre [:sources-count :count]) (from :genre-norm)) genre-ltree-transform))
    :paragraphs (seq-to-tree (q (-> (select :genre [:paragraphs-count :count]) (from :genre-norm)) genre-ltree-transform))
    :sentences  (seq-to-tree (q (-> (select :genre [:sentences-count :count]) (from :genre-norm)) genre-ltree-transform))
    :chunks     (seq-to-tree (q (-> (select :genre [:chunk-count :count]) (from :genre-norm)) genre-ltree-transform))
    :tokens     (seq-to-tree (q (-> (select :genre [:token-count :count]) (from :genre-norm)) genre-ltree-transform))}))

(def genre-names (delay (->> @norm-map :sources :children (map :name) set)))

(def ^:private decimal-format (java.text.DecimalFormat. "#.00"))
(defprotocol ICompactNumber
  (compact-number [num]))
(extend-protocol ICompactNumber

  java.lang.Double
  (compact-number [x]
    (Double/parseDouble (.format ^java.text.DecimalFormat decimal-format x)))

  clojure.lang.Ratio
  (compact-number [x]
    (Double/parseDouble (.format ^java.text.DecimalFormat decimal-format x)))

  java.lang.Long
  (compact-number [x] x)

  clojure.lang.BigInt
  (compact-number [x] x))

;; FIXME TODO add compact-numbers
;; TODO add natsume-units version
(defn get-search-tokens [query-map & {:keys [norm] :or {norm :tokens}}]
  (->> (qm {:select [:*]
            :from [:search-tokens]
            :where (map->and-query (select-keys query-map [:lemma :orth-base :pos-1 :pos-2]))}
          genre-ltree-transform)
       (group-by #(select-keys % [:lemma :orth-base :pos-1 :pos-2]))
       (map-vals seq-to-tree)
       ;; Optionally normalize results if :norm key is set and available.
       (?>> (contains? @norm-map norm) (map-vals #(normalize-tree (norm @norm-map) % :clean-up-fn compact-number)))
       ;; Below is for API/JSON TODO (might want to move below to service.clj) as it is more JSON/d3-specific
       vec
       (map #(hash-map :token (first %) :results (second %)))))
(comment
  (not= (get-search-tokens {:orth-base "こと"} :norm :sentences)
        (get-search-tokens {:orth-base "こと"})))

(defn get-one-search-token [query-map & {:keys [norm compact-numbers] :or {norm :tokens compact-numbers true}}]
  (->> (qm {:select [:*]
            :from [:search-tokens]
            :where (map->and-query (select-keys query-map [:lemma :orth-base :pos-1 :pos-2]))}
          genre-ltree-transform)
       seq-to-tree
       ;; Optionally normalize results if :norm key is set and available.
       (?>> (contains? @norm-map norm) (#(normalize-tree (norm @norm-map) % :clean-up-fn (if compact-numbers compact-number identity))))
       ;; Below is for API/JSON TODO (might want to move below to service.clj) as it is more JSON/d3-specific
       ))

;; Should contain all totals in a map by collocation type (n-gram size is determined by type) and genre.
(def gram-totals
  (delay
   (let [records (q (-> (select :*) (from :gram-norm)) #(update-in % [:type] underscores->dashes))]
     (->> records
          (map #(update-in % [:genre] ltree->seq))
          (group-by :type)
          (map-vals #(seq-to-tree % :merge-keys [:count :sentences-count #_:paragraphs-count :sources-count]))))))

(def gram-types
  (delay (set (q (-> (select :type) (from :gram-norm)) underscores->dashes :type))))

(def tokens-by-gram
  (delay
   (map-vals (fn [x] (reduce #(+ %1 (-> %2 val :count)) 0 x))
             (group-by #(let [wcs (clojure.string/split (name (key %)) #"-")
                              aux-count (count (filter (fn [wc] (= "auxiliary" wc)) wcs))]
                          (- (count wcs) aux-count))
                       @gram-totals))))

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
  [{:keys [string-1 string-2 string-3 string-4 type genre offset limit measure compact-numbers scale relation-limit]
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
    (->> (qm
          (-> {:select (vec (distinct (concat aggregates-clause selected)))
               :from [(keyword (str "search_gram_" n))]
               :where where-clause}
              (?> (not-empty selected) (assoc :group-by selected))))
         #_(map genre-ltree-transform)
         (?>> (> n 1) (map #(let [contingency-table (stats/expand-contingency-table
                                                      {:f-ii (:count %) :f-ix (:f-ix %) :f-xi (:f-xi %)
                                                       ;; FIXME : we probably want to have the option of using the total count per n-gram order...
                                                       :f-xx (-> @gram-totals type :count)})] ;; FIXME Should add :genre filtering to gram-totals when we specify some genre filter!
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
  (pprint (query-collocations {:string-1 "こと" :string-2 "が" :measure :t})))

(comment
  ;; tags code.
  (fn [m] (update-in m [:tags]
                    (fn [ts]
                      (->> ts
                           edn/read-string
                           (r/map (fn [t] (if (empty? t) :none (map keyword t))))
                           r/flatten
                           (into [])
                           frequencies))))

  #_(->> db-results
         (r/filter (fn [m] (set/subset? tags (set (keys (:tags m))))))
         (r/map (fn [m] (assoc m :count (apply + (for [[k v] (:tags m) :when (tags k)] v)))))
         (into [])))


;; FIXME / TODO: optimization: !! do not select :f-ix/:f-xi when measure is :count. !!
(defn query-collocations-tree
  [{:keys [string-1 string-2 string-3 string-4 type genre tags measure compact-numbers normalize?]
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
              db-results (qm {:select (vec (distinct (concat aggregates-clause selected #_[:tags])))
                              :from [(keyword (str "search_gram_" n))]
                              :where where-clause
                              :group-by selected #_(conj selected :tags)}
                             genre-ltree-transform)
              tree (seq-to-tree db-results)]
          (if (empty? db-results)
            tree
            (if normalize?
              (normalize-tree (get @gram-totals type) tree
                              :clean-up-fn (if compact-numbers compact-number identity))
              tree)))
        ;; n > 1
        (let [merge-stats
              (fn [record]
                (let [contingency-table
                      (stats/expand-contingency-table
                       {:f-ii (:count record)
                        :f-ix (:f-ix record)
                        :f-xi (:f-xi record)
                        :f-xx (get @tokens-by-gram n) #_(-> @gram-totals type :count)})]
                  (-> record
                      (merge contingency-table)
                      (merge (for-map [m (set/difference measure #{:count :f-ix :f-xi :log-dice})]
                                 m ((m stats/association-measures-graph) contingency-table))))))

              db-results
              (qm {:select (vec (distinct (concat aggregates-clause selected)))
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
                (seq-to-tree :merge-fns merge-fns :root-values (select-keys (merge-stats (into {} (r/reduce (fn [a kvs] (merge-with merge-fns a kvs)) {} (map #(dissoc % :genre) db-results)))) measure))
                (?> (and (:count measure) normalize?) ((fn [tree] (normalize-tree (get @gram-totals type) tree :clean-up-fn (if compact-numbers compact-number identity))))))))))))

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
   :where (map->and-query query)
   })

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
  [{:keys [type limit offset genre html sort order]
    :or {limit 6 offset 0 type :noun-particle-verb}
    :as m}]
  (let [sort (if sort
               (if (re-seq #"(?i)^(length|tokens|chunks|jlpt.?level|bccwj.?level|link.?dist|chunk.?depth)$" sort) (str (name (underscores->dashes sort)) " ASC") "abs(4 - chunks)")
               "abs(4 - chunks)")
        query-fields (select-keys m [:string-1 :string-2 :string-3 :string-4])
        selected-fields [:sources.genre :sources.title :sources.author :sources.year :sentences.text]
        n (-> type name (string/split #"-") count)
        search-table (keyword (str "gram_" n))
        begin-end-fields (for [number (range 1 (inc n))
                               s ["begin-" "end-"]]
                           (keyword (str s number)))]
    (q {:select [(h/call :setseed 0.2)]}) ; FIXME Better way to get consistent ordering? -> when setting connection?
    (qm
     {:select [:*]
      :where [:<= :part.r limit]
      :from [[{:select (-> selected-fields
                           (concat begin-end-fields)
                           (conj [(h/raw (str "ROW_NUMBER() OVER (PARTITION BY subltree(sources.genre, 0, 1) ORDER BY RANDOM(), " sort ")")) :r]))
               :from [search-table :sentences :sources]
               :where (-> (conj (map->and-query query-fields)
                                [:= :sentences-id :sentences.id]
                                [:= :sentences.sources-id :sources.id])
                          (?> genre (conj [:tilda :sources.genre genre])))
               :group-by (apply conj selected-fields :sentences.chunks begin-end-fields)}
              :part]]}
     genre-ltree-transform
     #(dissoc % :r)
     (if html tag-html identity))))

(comment
  (query-sentences {:string-1 "こと" :type :noun-particle-verb})
  (query-sentences {:string-1 "こと" :type :noun-particle-verb-particle :genre ["書籍" "*"] :limit 10 :html true}))

(defn mi-score [collocation]
  ;; If no gram found in 1-gram DB, use 1 or 0.5 for freq.
  (let [n (count (:type-vector collocation))
        strings [:string-1 :string-2 :string-3 :string-4]
        ;; Get counts for each string-*, if possible.
        db-results (map query-collocations-tree
                        (for [i (take n (range (count strings)))]
                          (-> (apply dissoc collocation strings)
                              (assoc :measure #{:count :mi}
                                     :normalize? false
                                     :type (nth (:type-vector collocation) i)
                                     :string-1 ((nth strings i) collocation)))))
        freqs (->> db-results (map :count) (map #(if (or (nil? %) (zero? %)) 1.0 %)))
        f-xx (get @tokens-by-gram n) #_(:count ((:type collocation) @gram-totals)) ;; Count for all genres.
        mi-scores (let [f-ii 1.0
                        f-ix (first freqs)
                        f-xi (if (= 2 (count freqs)) (nth freqs 1) (nth freqs 2))]
                    (select-keys
                     (stats/compute-association-measures
                      (stats/expand-contingency-table
                       {:f-ii f-ii :f-ix f-ix :f-xi f-xi :f-xx f-xx}))
                     [:mi :t :llr]))]
    {:stats (map-vals compact-number mi-scores)
     :found? false}))

(defn sigma-score [pos n tree]
  (if-let [freqs (->> tree :children (map (juxt :name :count)) (into {}) seq)]
    (let [freqs (let [diff (- (count @genre-names) (count freqs))]
                  (if (pos? diff)
                    (concat freqs (seq (zipmap (set/difference @genre-names (set (map first freqs))) (repeat diff 0.0))))
                    freqs))
          mean (stats/mean (vals freqs))
          sd (stats/sd (vals freqs))
          ;; df = 11 (BCCWJ + STJC + Wikipedia?)
          ;; 0.10      	0.05 	0.025 	0.01 	0.005
          ;; 17.275 	19.675 	21.920 	24.725 	26.757
          chisq-line (case pos
                       :noun 26.757
                       :verb 26.757
                       :particle 26.757
                       :auxiliary-verb 26.757
                       19.675)
          chisq-fn #(let [chi (/ (Math/pow (- % mean) 2.0) mean)]
                      (if (> chi chisq-line)
                        (- % mean)
                        nil))
          chisq-filtered (into {} (r/remove #(nil? (second %)) (map-vals chisq-fn freqs)))
          good-sum (if-let [good-vals (vals (select-keys chisq-filtered ["白書" "科学技術論文" "法律"]))]
                     (/ (reduce + good-vals) (count good-vals)))
          bad-sum (if-let [bad-vals (vals (select-keys chisq-filtered ["Yahoo_知恵袋" "Yahoo_ブログ" "国会会議録"]))]
                    (/ (reduce + bad-vals) (count bad-vals)))]
      (-> {:register-score {:good (compact-number (if (number? good-sum) good-sum 0.0))
                            :bad  (compact-number (if (number? bad-sum) bad-sum 0.0))
                            :mean (compact-number (if (number? mean) mean 0.0))
                            :verdict (cond
                                      (and (and good-sum (>= good-sum 0.0)) (and bad-sum (neg? bad-sum))) :good
                                      (and (and good-sum (<= good-sum 0.0)) (and bad-sum (pos? bad-sum))) :bad
                                      :else :unknown)}
           :found? true}
          (?> (> n 1) (assoc :stats (map-vals compact-number (select-keys tree [:count :mi :t :llr])))))
      #_(if (and (and good-sum (>= good-sum 0.0)) (and bad-sum (neg? bad-sum)))
        {:found? true}
        (if (and (and good-sum (<= good-sum 0.0)) (and bad-sum (pos? bad-sum)))
          (-> {:register-score {:good (compact-number (if (number? good-sum) good-sum 0.0))
                                :bad  (compact-number bad-sum)
                                :mean (compact-number mean)}
               :found? true}
              (?> (> n 1) (assoc :stats (map-vals compact-number (select-keys tree [:count :mi :t :llr])))))
          {:found? true})))
    {:found? false}))

(defn token-register-score
  "Old formula, but include measures other than chi-sq."
  [query]
  (let [results (get-one-search-token query :compact-numbers false)]
    (sigma-score (:pos query) 1 results)))

(defn collocation-register-score [query]
  (let [collocation (-> (zipmap [:string-1 :string-2 :string-3 :string-4]
                                (mapcat (fn [part] (remove nil? [(:head-string part) (:tail-string part)]))
                                        (:data query)))
                        (assoc :compact-numbers false
                               :measure #{:count :mi :f-xi :f-ix}
                               :type-vector (:type query)
                               :type (->> (:type query)
                                          (map name)
                                          (clojure.string/join "-")
                                          keyword)))
        tree (query-collocations-tree collocation)
        n (count (:type-vector collocation))]
    (if (seq (:children tree))
      (sigma-score :collocation n tree)
      (if (> n 1)
        (mi-score collocation)
        -2))))
