(ns natsume-server.component.query
  (:require [clojure.string :as string]
            [clojure.set :as set]
            [clojure.core.reducers :as r]

            [hugsql.core :as hugsql]

            [honeysql.core :as h]
            [honeysql.format :as fmt]
            [honeysql.helpers :refer :all :exclude [update]]
            [clojure.core.cache :as cache]

            [d3-compat-tree.tree :refer [seq-to-tree normalize-tree]]

            [natsume-server.models.pg-types :refer :all]
            [natsume-server.utils.naming :refer [dashes->underscores underscores->dashes]]
            [natsume-server.utils.export :as export]

            [natsume-server.nlp.stats :as stats]

            [natsume-server.utils.numbers :refer [compact-number]]
            [plumbing.core :refer [map-keys map-vals for-map ?> ?>>]]

            [plumbing.core :refer [fn->>]]
            [schema.core :as s]

            [mount.core :refer [defstate]]
            [natsume-server.config :refer [config]]
            [natsume-server.component.database :as db :refer [connection !norm-map !genre-names !genre-tokens-map !gram-totals !gram-types !tokens-by-gram]]
            [clojure.string :as str]
            [taoensso.timbre :as timbre]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [hugsql.adapter :as adapter]
            [natsume-server.utils.fs :as fs]))

;; ## Database wrapper functions

;; Memoized q using LRU (Least Recently Used) strategy.
;; TODO: consider using LU.
(def !query-cache
  (atom (cache/lru-cache-factory {} :threshold 50000)))
(defn qm
  ([query]
   (if (cache/has? @!query-cache query)
     (get (swap! !query-cache #(cache/hit % query)) query)
     (let [new-value (db/q query)]
       (swap! !query-cache #(cache/miss % query new-value))
       new-value)))
  ([query row-fn]
   (let [query-trans [query row-fn]]
     (if (cache/has? @!query-cache query-trans)
       (first (get (swap! !query-cache #(cache/hit % query-trans)) query-trans))
       (let [new-value (db/q query row-fn)]
         (swap! !query-cache #(cache/miss % query-trans [new-value row-fn]))
         new-value)))))

(defn i1!
  [tbl-name row]
  (sql/insert! (:datasource connection) tbl-name row {:table-fn dashes->underscores :column-fn dashes->underscores}))

(defn i!
  [tbl-name rows]
  (let [ks (keys (first rows))]
    (sql/insert-multi! (:datasource connection) tbl-name ks
                       (map (apply juxt ks) rows)
                       {:table-fn dashes->underscores :column-fn dashes->underscores})))

(defn map->and-query [qs]
  (reduce-kv #(conj %1 [:= %2 %3]) [:and] qs))

;; Query

;; TODO Factor this out and clean up.
(deftype HugsqlAdapterNextJdbc []

  adapter/HugsqlAdapter
  (execute [this db sqlvec options]
    (jdbc/execute! (:datasource db) sqlvec
                   (if (some #(= % (:command options)) [:insert :i!])
                     {:return-keys true}
                     (:command-options options))))

  (query [this db sqlvec options]
    (sql/query (:datasource db) sqlvec {:builder-fn as-kebab-maps :table-fn dashes->underscores} #_(:command-options options)))

  (result-one [this result options]
    (first result))

  (result-many [this result options]
    result)

  (result-affected [this result options]
    (:next.jdbc/update-count (first result)))

  (result-raw [this result options]
    result)

  (on-exception [this exception]
    (throw exception)))

(defn hugsql-adapter-next-jdbc []
  (->HugsqlAdapterNextJdbc))
(hugsql/set-adapter! (hugsql-adapter-next-jdbc))
(hugsql/def-db-fns "natsume_server/component/sql/fulltext.sql")

(defn re-pos [re s]
  (loop [m (re-matcher re s)
         res []]
    (if (.find m)
      (recur m (conj res [(.start m) (.end m)]))
      res)))

(defn kwic-regex-formatter
  "Returns a map of the matched search key and text before and, one for each match."
  [rx text]
  (let [matches (re-pos rx text)]
    (map
      (fn [[begin end]]
        (let [before-string (subs text 0 begin)
              after-string (subs text end (count text))
              hl-string (subs text begin end)]
          {:before before-string
           :key    hl-string
           :after  after-string}))
      matches)))

(def !fulltext-query-cache
  (atom (cache/lru-cache-factory {} :threshold 5)))

;; TODO add download feature -> where to put file and how to authenticate/timeout?
(defn query-fulltext [conn {:keys [query genre remove-tags limit offset]
                            :or   {limit 1000 offset 0}}]
  (let [cache-key [query genre remove-tags]]
    (if (cache/has? @!fulltext-query-cache cache-key)
      (update (get (swap! !fulltext-query-cache #(cache/hit % cache-key)) cache-key)
              :matches (fn [matches]
                         (->> matches
                              (drop offset)
                              (take limit))))
      (let [results (sequence
                      (comp (mapcat (fn [{:keys [id tags title author year genre
                                                 before-text key-text after-text]}]
                                      (let [matches (kwic-regex-formatter (re-pattern query) key-text)]
                                        (for [match matches]
                                          {:id     id
                                           :tags   (into #{} (map keyword tags))
                                           :title  title
                                           :author author
                                           :year   year
                                           :genre  (str/join "." genre)
                                           :before (str before-text (:before match))
                                           :key    (:key match)
                                           :after  (str (:after match) after-text)}))))
                            (remove (fn [m]
                                      (if (or (empty? remove-tags) ;; Filter not set.
                                              (empty? (:tags m))) ;; Sentence has no tags.
                                        false
                                        ;; If any of filter-tags appears in map, remove.
                                        (some remove-tags (:tags m))))))
                      (fulltext-stream conn {:query query :genre genre})) ;; Chsk router `event-msg-handler` error: [:fulltext/matches {:query "^あと [,、] ", :genre ".*", :limit 100, :offset 0, :remove-tags #{}}]
            paginated-matches (->> results (drop offset) (take limit))
            total-count (count results)
            patterns (frequencies (map :key results))
            results-file (str "fulltext-matches-" (export/sha256 (str query "#" genre "#" remove-tags)) ".xlsx")
            _ (export/save-spreadsheet! (str fs/tmp-path "/" results-file) "文検索結果" results [:id :tags :before :key :after :genre :title :author :year])
            results-map {:matches     paginated-matches
                         :total-count total-count
                         :patterns    patterns
                         :file        (str "/" results-file)}]
        (swap! !fulltext-query-cache
               #(cache/miss % cache-key {:matches results :total-count total-count :patterns patterns :file results-file}))
        results-map))))

(defn query-expanded-document [conn id]
  (update (expand-document conn {:id id})
          :genre (partial str/join ".")))

;; ## Insertion functions

;; ### Sources

(defn insert-source! [sources-metadata]
  (i1! :sources sources-metadata))

(defn insert-sources!
  "Inserts sources meta-information from the corpus into the database."
  [sources-metadata]
  (i! :sources sources-metadata))

;; ### Sentence

(s/defn insert-sentence :- [{:id s/Num s/Keyword s/Any}]
  [sentence-values]
  (i1! :sentences
       (select-keys sentence-values (db/schema-keys db/sentences-schema))))

;; ### Collocations
(defn insert-collocations! [collocations sentences-id]
  (doseq [collocation collocations]
    (let [grams (count (:type collocation))
          record-map (apply merge
                            (for [i (range 1 (inc grams))]
                              (let [record (nth (:data collocation) (dec i))]
                                (map-keys #(let [[f s] (string/split (name %) #"-")]
                                             (keyword (str s "-" i)))
                                          (-> record
                                              (?> (:chunk/head-pos record) (update-in [:chunk/head-pos] name))
                                              (?> (:chunk/tail-pos record) (update-in [:chunk/tail-pos] name))
                                              (?> (:chunk/head-tags record) (update-in [:chunk/head-tags] (fn->> (map name) (into #{}))))
                                              (?> (:chunk/tail-tags record) (update-in [:chunk/tail-tags] (fn->> (map name) (into #{})))))))))]
      (i1! (keyword (str "gram-" grams))
           (assoc record-map :sentences-id sentences-id)))))

;; ### Unigrams
(defn insert-unigrams! [unigrams sentences-id]
  (when (seq unigrams)
    (i! :unigrams
        (map-indexed
          (fn [i token]
            (assoc token :position i :sentences-id sentences-id))
          unigrams))))

;; ### Tokens
(defn insert-tokens! [token-seq sentences-id]
  (i! :tokens
      (map-indexed
        (fn [i token]
          (-> token
              (select-keys [:morpheme/pos :morpheme/pos-1 :morpheme/pos-2 :morpheme/pos-3 :morpheme/pos-4 :morpheme/c-type :morpheme/c-form :morpheme/lemma :morpheme/orth :morpheme/pron :morpheme/orth-base :morpheme/pron-base :morpheme/goshu :morpheme/tags])
              (assoc :position i
                     :sentences-id sentences-id)
              ;; For the tokens table, we prefer getting rid of extraneous whitespace for better matching capabilities.
              (update :morpheme/orth (fn [s] (string/replace s #"[\n\t　\s]" "")))))
        token-seq)))

;; ## Query functions

(defn basename->source-id
  [basename]
  (-> (db/q (-> (select :id)
                (from :sources)
                (where [:= :basename basename])))
      first
      :id))

(defn get-genres []
  (distinct (map :genre
                 (db/q (-> (select :genre)
                           (from :sources)
                           (order-by :genre))))))

(defn get-genre-counts []
  (db/q {:select   [:genre [(h/call :count :*) :count]]
         :from     [:sources]
         :group-by [:genre]}))

(defn genres->tree []
  (seq-to-tree (get-genre-counts)))

(defn sources-id->genres-map [sources-id]
  (->> (db/q {:select [:genre]
              :from   [:sources]
              :where  [:= :id sources-id]})
       (map :genre)
       distinct))

(defn sentences-by-genre [genre]
  (let [query (if (< (count genre) 4) (conj genre "*") genre)]
    (map :text
         (db/q (-> (select :text)
                   (from :sentences :sources)
                   (where [:and
                           [:= :sentences.sources_id :sources.id]
                           [:tilda :sources.genre query]]))))))

(defn tokens-by-genre [genre & {:keys [field] :or {field :lemma}}]
  (let [query (if (< (count genre) 4) (conj genre "*") genre)]
    (mapcat vals
            (db/q (-> (select (h/raw (str "string_agg(tokens." (name field) ", ' ')")))
                      (from :tokens :sentences :sources)
                      (where [:and
                              [:= :tokens.sentences-id :sentences.id]
                              [:= :sentences.sources-id :sources.id]
                              [:tilda :sources.genre genre]])
                      (group :tokens.sentences-id))))))


(defn all-sentences-with-genre []
  (db/q (-> (select :text :sources.genre)
            (from :sentences :sources)
            (where [:= :sentences.sources_id :sources.id])
            (group :sources.genre :sentences.id))))

(defn sources-ids-by-genre [genre]
  (let [query (if (< (count genre) 4) (conj genre "*") genre)]
    (map :id
         (db/q (-> (select :id)
                   (from :sources)
                   (where [:tilda :genre query]))))))

(defn sources-text [id]
  (map :text
       (db/q (-> (select :text)
                 (from :sentences :sources)
                 (where [:and
                         [:= :sources.id id]
                         [:= :sentences.sources_id :sources.id]])))))

(defn sources-tokens [id & {:keys [field] :or {field :lemma}}]
  (mapcat vals
          (db/q (-> (select (h/raw (str "string_agg(tokens." (name field) ", ' ')")))
                    (from :tokens :sentences :sources)
                    (where [:and
                            [:= :sources.id id]
                            [:= :tokens.sentences-id :sentences.id]
                            [:= :sentences.sources_id :sources.id]])
                    (group :tokens.sentences-id)))))

;; FIXME TODO add compact-numbers
;; TODO add natsume-units version
(defn get-search-tokens [query-map & {:keys [norm] :or {norm :tokens}}]
  (->> (qm {:select [:*]
            :from   [:search-tokens]
            :where  (map->and-query (select-keys query-map [:lemma :orth-base :pos-1 :pos-2]))})
       (group-by #(select-keys % [:lemma :orth-base :pos-1 :pos-2]))
       (map-vals seq-to-tree)
       ;; Optionally normalize results if :norm key is set and available.
       (?>> (contains? db/!norm-map norm) (map-vals #(normalize-tree (norm db/!norm-map) % {:clean-up-fn compact-number})))
       ;; Below is for API/JSON TODO (might want to move below to service.clj) as it is more JSON/d3-specific
       vec
       (map #(hash-map :token (first %) :results (second %)))))
(comment
  (not= (get-search-tokens {:orth-base "こと"} :norm :sentences)
        (get-search-tokens {:orth-base "こと"})))

(defn get-one-search-token
  [query-map & {:keys [norm compact-numbers] :or {norm :tokens compact-numbers true}}]
  (->> (qm {:select [:*]
            :from   [:search-tokens]
            :where  (map->and-query (select-keys query-map [:lemma :orth-base :pos-1 :pos-2]))})
       seq-to-tree
       ;; Optionally normalize results if :norm key is set and available.
       (?>> (and norm (contains? db/!norm-map norm)) (#(normalize-tree (norm db/!norm-map) % {:clean-up-fn (if compact-numbers compact-number identity)})))))

;; TODO custom-query to supplement and-query type queries (i.e. text match LIKE "%考え*%")
;; FIXME shape of data returned should be the same for all types of queries.
;; FIXME normalization is needed for seq-tree -- another reason to split function. The implementation should address the issue of normalizing before measure calculations, otherwise they will not be done correctly???? Is the contingency table per genre????
(defn query-collocations
  "Query n-gram tables with the following optional parameters:
  -   type: collocation type (default :noun-particle-verb)
  -   aggregates: fields to sum over (default: [:count :f-io :f-oi])
  -   selected: fields to return (default: [:string-1 :string-2 :string-3 :type :genre])
  -   genre: optionally filter by genre (default: all data)"
  [{:keys [string-1 string-2 string-3 string-4 type genre offset limit measure compact-numbers scale relation-limit]
    :or   {type            :noun-particle-verb
           measure         :count
           compact-numbers true
           scale           false}
    :as   m}]
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
    (->> (qm (-> {:select (vec (distinct (concat aggregates-clause selected)))
                  :from   [(keyword (str "search_gram_" n))]
                  :where  where-clause}
                 (?> (not-empty selected) (assoc :group-by selected))))
         (?>> (> n 1) (map #(let [contingency-table (stats/expand-contingency-table
                                                      {:f-ii (:count %) :f-ix (:f-ix %) :f-xi (:f-xi %)
                                                       ;; FIXME : we probably want to have the option of using the total count per n-gram order...
                                                       :f-xx (-> !gram-totals type :count)})] ;; FIXME Should add :genre filtering to !gram-totals when we specify some genre filter!
                              (case measure
                                :log-dice (merge % contingency-table)
                                :count %                    ;; FIXME count should be divided by :f-xx (see above), especially when filtering by genre.
                                (assoc % measure
                                         ((measure stats/association-measures-graph) contingency-table))))))
         (?>> (= :log-dice measure) ((fn [coll] (stats/log-dice coll (if (:string-1 query) :string-3 :string-1)))))
         (map #(-> % (dissoc :f-ii :f-io :f-oi :f-oo :f-xx :f-ix :f-xi :f-xo :f-ox) (?> (not= :count measure) (dissoc :count))))
         (sort-by (comp - measure))                         ;; FIXME group-by for offset+limit after here, need to modularize this following part to be able to apply on groups
         (?>> (:string-2 selected) ((fn [rows] (->> rows
                                                    (group-by :string-2)
                                                    (map-vals (fn [row] (map #(dissoc % :string-2) row)))
                                                    ;; Sorting assumes higher measure values are better.
                                                    (sort-by #(- (apply + (map measure (second %)))))
                                                    (map (fn [[p fs]] {:string-2 p
                                                                       :data     (clean-up-fn fs)}))
                                                    (?>> relation-limit (take relation-limit))))))
         (?>> (not (:string-2 selected)) (clean-up-fn)))))
;; FIXME include option for human-readable output (log-normalized to max): scale option

(comment
  (use 'clojure.pprint)
  (pprint (query-collocations conn {:string-1 "こと" :string-2 "が" :measure :t})))


;; FIXME / TODO: optimization: !! do not select :f-ix/:f-xi when measure is :count. !!
(defn query-collocations-tree
  [{:keys [string-1 string-2 string-3 string-4 type genre tags measure compact-numbers normalize?]
    :or   {type            :noun-particle-verb
           measure         #{:count}                        ;; (keys stats/association-measures-graph)
           normalize?      true
           compact-numbers true}
    :as   m}]
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
        merge-fns (for-map [m measure] m (if (#{:count :f-xi :f-ix #_:f-io #_:f-oi} m) + #(or %1 %2)))
        clean-up-fn (fn [data] (->> data
                                    (?>> compact-numbers (map (fn [r] (for-map [[k v] r] k (if (measure k) (compact-number v) v)))))))]
    (if (= (count query) n)                                 ;; Only fully-specified queries are allowed.
      (if (= n 1)                                           ;; 1-gram is a specialized case, as it uses :tags. -> not anymore.
        (let [tags (if (empty? tags) #{:none} tags)
              db-results (qm {:select   (vec (distinct (concat aggregates-clause selected #_[:tags])))
                              :from     [(keyword (str "search_gram_" n))]
                              :where    where-clause
                              :group-by selected #_(conj selected :tags)})
              tree (seq-to-tree db-results)]
          (if (empty? db-results)
            tree
            (if normalize?
              (normalize-tree (get !gram-totals type) tree
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
                         :f-xx (get !tokens-by-gram n) #_(-> !gram-totals type :count)})]
                  (-> record
                      (merge contingency-table)
                      (merge (for-map [m (set/difference measure #{:count :f-ix :f-xi :log-dice})]
                                      m ((m stats/association-measures-graph) contingency-table))))))

              db-results
              (qm {:select   (vec (distinct (concat aggregates-clause selected)))
                   :from     [(keyword (str "search_gram_" n))]
                   :where    where-clause
                   :group-by selected}
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
                (?> (and (:count measure) normalize?) ((fn [tree] (normalize-tree (get !gram-totals type) tree {:clean-up-fn (if compact-numbers compact-number identity)})))))))))))

(comment

  (pprint (query-collocations-tree {:string-1 "こと" :string-2 "が" :string-3 "できる" :measure :t})))

;; TODO look for similarities in query-collocations and below: the selection seems a bit better here?? for tokens, we know the name of the fields ahead of time, while with grams, we have to choose per n
;; TODO graph?
(defn query-tokens
  [{:keys [query selected aggregates]
    :or   {selected   [:lemma :orth-base :pos-1 :pos-2 :genre]
           aggregates [:count]}}]
  {:select (set/difference (set (concat selected aggregates))
                           (set (keys query)))
   :from   [:search-tokens]
   :where  (map->and-query query)})

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
  [{:keys [type limit offset genre html sort]
    :or   {limit 6 offset 0 type :noun-particle-verb}
    :as   m}]
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
    (db/q {:select [(h/call :setseed 0.2)]})           ; FIXME Better way to get consistent ordering? -> when setting connection?
    (qm {:select [:*]
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
        (comp (if html tag-html identity)
              #(dissoc % :r)))))

(comment
  (query-sentences conn {:string-1 "こと" :type :noun-particle-verb})
  (query-sentences conn {:string-1 "こと" :type :noun-particle-verb-particle :genre ["書籍" "*"] :limit 10 :html true}))

(defn query-sentences-tokens
  "Query sentences containing given tokens, up to 'limit' times per top-level genre.
  Including the optional genre parameter will only return sentences from given genre, which can be any valid PostgreSQL ltree query."
  [{:keys [limit offset genre html sort]                    ;; FIXME lemma should be optional--instead we should require some set of orth/lemma/pron etc.
    :or   {limit 6 offset 0}
    :as   m}]
  (let [sort (if sort
               (if (re-seq #"(?i)^(length|tokens|chunks|jlpt.?level|bccwj.?level|link.?dist|chunk.?depth)$" sort) (str (name (underscores->dashes sort)) " ASC") "abs(4 - chunks)")
               "abs(4 - chunks)")
        query-fields (select-keys m [:orth :orth-base :lemma :pron :pron-base :pos-1 :pos-2 :pos-3 :pos-4 :c-form :c-type :goshu])
        selected-fields [:sources.genre :sources.title :sources.author :sources.year :sentences.text]
        search-table :tokens]
    (db/q {:select [(h/call :setseed 0.2)]})           ; FIXME Better way to get consistent ordering? -> when setting connection?
    (qm {:select [:*]
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
        (comp (if html tag-html identity)
              #(dissoc % :r)))))
