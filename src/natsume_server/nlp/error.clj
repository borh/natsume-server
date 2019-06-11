(ns natsume-server.nlp.error
  (:require [schema.core :as s]
            [plumbing.core :refer [?> ?>> map-vals for-map fnk]]
            [d3-compat-tree.tree :refer [normalize-tree]]
            [natsume-server.nlp.text :as text]
            [natsume-server.nlp.annotation-middleware :as anno]
            [natsume-server.component.database :as db]
            [natsume-server.component.query :as q]
            [natsume-server.nlp.collocations :as coll]
            [natsume-server.nlp.stats :as stats]
            [natsume-server.utils.numbers :refer [compact-number]]
            [clojure.core.reducers :as r]))

(s/defn sigma-score :- {s/Keyword s/Any}
  [pos  :- s/Keyword
   n    :- s/Num
   tree :- {s/Keyword s/Any}]
  (let [raw-freqs
        (let [m (->> tree ;; TODO/FIXME: actually, token frequencies should be normalized based on (corpus, pos) and not just on (corpus)
                     ;; (#(normalize-tree (:pos db/!pos-genre-tokens) %)) ;; TODO
                     :children
                     (map (juxt :name :count))
                     (into {}))]
          (for-map [genre db/!genre-names]
            genre (or (get m genre) 0)))

        freqs
        (->> tree
             (#(normalize-tree (:chunk/tokens db/!norm-map) %))
             :children
             (map (juxt :name :count))
             (into {}))]
    (if-not (seq freqs)
      {:found? false}
      (let [freqs (for-map [genre db/!genre-names]
                    genre (or (get freqs genre) 0.0))
            mean (stats/mean (vals freqs))
            ;;sd (stats/sd (vals freqs))
            ;; df = 11 (BCCWJ + STJC + Wikipedia?)
            ;; 0.10      	0.05 	0.025 	0.01 	0.005
            ;; 17.275 	19.675 	21.920 	24.725 	26.757
            good-unfiltered-ppm (* (/ (reduce + (vals (select-keys raw-freqs ["白書" "科学技術論文" "法律"])))
                                      (reduce + (vals (select-keys db/!genre-tokens-map ["白書" "科学技術論文" "法律"]))))
                                   1000000)
            chisq-line (case pos
                         :noun 26.757
                         :verb 26.757
                         :particle 26.757
                         :adverb 26.757 #_0.0 #_17.275
                         :auxiliary-verb 26.757
                         19.675)
            chisq-fn (s/fn :- s/Num [x :- s/Num]
                       (/ (Math/pow (- x mean) 2.0) mean))
            chisq-corpora (map-vals chisq-fn freqs)
            chisq-filter #(if (> (chisq-fn %) chisq-line)
                           (- % mean)
                           nil)
            chisq-filtered (into {} (r/remove #(nil? (second %)) (map-vals chisq-filter freqs)))
            good-sum (if-let [good-vals (vals (select-keys chisq-filtered ["白書" "科学技術論文" "法律"]))]
                       (/ (reduce + good-vals) (count good-vals)))
            bad-sum (if-let [bad-vals (vals (select-keys chisq-filtered ["Yahoo_知恵袋" "Yahoo_ブログ" "国会会議録"]))]
                      (/ (reduce + bad-vals) (count bad-vals)))]
        ;; FIXME never score something bad that has occurs over a certain ammount in good-sum (0.5% ?)
        (-> {:register-score {:good    (compact-number (if (number? good-sum) good-sum 0.0))
                              :bad     (compact-number (if (number? bad-sum) bad-sum 0.0))
                              :mean    (compact-number (if (number? mean) mean 0.0))
                              :freqs   freqs
                              :raw-freqs raw-freqs
                              :chisq   chisq-corpora
                              :verdict (cond
                                         (and (and good-sum (>= good-sum 0.0)) (and bad-sum (neg? bad-sum))) true
                                         (and #_(<= good-unfiltered-ppm 0.5) (and good-sum (<= good-sum 0.0)) (and bad-sum (pos? bad-sum))) false
                                         :else nil)}
             :found?         true}
            (?> (> n 1) (assoc :stats (map-vals compact-number (select-keys tree [:count :mi :t :llr])))))))))

(defn mi-score [conn collocation]
  ;; If no gram found in 1-gram DB, use 1 or 0.5 for freq.
  (let [n (count (:type-vector collocation))
        strings [:string-1 :string-2 :string-3 :string-4]
        ;; Get counts for each string-*, if possible.
        db-results (map (partial q/query-collocations-tree conn)
                        (for [i (take n (range (count strings)))]
                          (-> (apply dissoc collocation strings)
                              (assoc :measure #{:count :mi}
                                     :normalize? false
                                     :type (nth (:type-vector collocation) i)
                                     :string-1 ((nth strings i) collocation)))))
        freqs (->> db-results (map :count) (map #(if (or (nil? %) (zero? %)) 1.0 %)))
        f-xx (get db/!tokens-by-gram n) #_(:count ((:type collocation) @!gram-totals)) ;; Count for all genres.
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

(defn collocation-register-score [conn query]
  (let [collocation (-> (zipmap [:string-1 :string-2 :string-3 :string-4]
                                (mapcat (fn [part] (remove nil? [(:chunk/head-string part) (:chunk/tail-string part)]))
                                        (:data query)))
                        (assoc :compact-numbers false
                               :measure #{:count :mi :f-xi :f-ix}
                               :type-vector (:type query)
                               :type (->> (:type query)
                                          (map name)
                                          (clojure.string/join "-")
                                          keyword)))
        tree (q/query-collocations-tree collocation)
        n (count (:type-vector collocation))]
    (if (seq (:children tree))
      (sigma-score :collocation n tree)
      (if (> n 1)
        (mi-score conn collocation)
        -2))))

(s/defn token-register-score
  "Old formula, but includes measures other than chi-sq."
  [conn query]
  (let [results (q/get-one-search-token query :compact-numbers false :norm nil)]
    (sigma-score (:morpheme/pos query) 1 results)))

(s/defn score-sentence [conn tree sentence]
  (let [tokens (->> tree
                    (mapcat :chunk/tokens)
                    (remove (fn [{:keys [morpheme/pos pos-1 pos-2]}] (or (= pos :symbol) (and (= pos-1 "助詞") (or (= pos-2 "格助詞") (= pos-2 "係助詞"))))))
                    (pmap (fn [m]
                            (let [register-score (token-register-score conn m)
                                  response {:type           :token
                                            :tags           (:tags m)
                                            :morpheme/pos   (:morpheme/pos m)
                                            :morpheme/begin (:morpheme/begin m)
                                            :morpheme/end   (:morpheme/end m)
                                            :morpheme/lemma (:morpheme/lemma m)
                                            :string         (:morpheme/orth m)}]
                              (if (map? register-score)
                                (merge response register-score)
                                response))))
                    (into []))
        collocations (->> tree
                          coll/extract-collocations
                          (filter identity) ;; FIXME ぞれ NPE problem
                          (remove (fn [m] (= (:type m) [:verb :verb]))) ;; FIXME
                          (pmap (fn [m]
                                  (let [record
                                        {:type         :collocation
                                         :morpheme/pos (:type m)
                                         :tags         (:tags m)
                                         :parts        (->> m
                                                            :data
                                                            (r/map (fn [part]
                                                                     (let [begin (or (:chunk/head-begin part) (:chunk/tail-begin part))
                                                                           end (or (:chunk/head-end part) (:chunk/tail-end part))
                                                                           pos (or (:chunk/head-pos part) (:chunk/tail-pos part))
                                                                           tags (or (:chunk/head-tags part) (:chunk/tail-tags part))
                                                                           lemma (or (:chunk/head-string part) (:chunk/tail-string part))]
                                                                       {:morpheme/begin begin
                                                                        :morpheme/end   end
                                                                        :morpheme/pos   pos
                                                                        :tags           tags
                                                                        :morpheme/lemma lemma
                                                                        :string         (subs sentence begin end) #_(:chunk/head-string part) #_(:chunk/tail-string part)})))
                                                            (into []))}
                                        register-score (collocation-register-score conn m)]
                                    (-> record
                                        (assoc :string (clojure.string/join (map :string (:parts record))))
                                        (?> (map? register-score) (merge register-score))))))
                          (into []))]
    (concat tokens collocations)))

(s/defn get-error :- {:results [{s/Keyword s/Any}] :parsed-tokens [{s/Keyword s/Any}]}
  [conn :- s/Any
   text :- s/Str]
  (if-let [paragraphs (->> text vector text/lines->paragraph-sentences)]
    (let [update-positions (fn [m offset] (-> m (update-in [:morpheme/begin] + offset) (update-in [:morpheme/end] + offset)))

          [scored-sentences parsed-tokens]
          (loop [ss (for [paragraph paragraphs sentence paragraph] sentence)
                 offset 0
                 parsed-tokens []
                 results []]
            (if-let [s (first ss)]
              (let [tree (anno/sentence->tree s)
                    token-seq (mapv #(select-keys % [:morpheme/orth :orth-base :morpheme/lemma :pos-1 :pos-2 :morpheme/c-form :morpheme/c-type :tags]) (mapcat :chunk/tokens tree))
                    scored-s (score-sentence conn tree s)
                    length-s (count s)
                    new-offset (+ offset length-s)
                    nl? (and (< new-offset (count text)) (= \newline (first (subs text new-offset (inc new-offset)))))]
                (recur (next ss)
                       (+ new-offset (if nl? 1 0))
                       (concat parsed-tokens (if nl? (conj token-seq {:morpheme/orth "\n" :orth-base "\n" :morpheme/lemma "\n" :pos-1 "補助記号" :pos-2 "*" :morpheme/c-form "*" :morpheme/c-type "*"}) token-seq))
                       (concat results
                               (map (fn [m] (case (:type m)
                                              :token (update-positions m offset)
                                              :collocation (update-in m [:parts]
                                                                      (fn [parts]
                                                                        (mapv (fn [part]
                                                                                (update-positions part offset))
                                                                              parts)))))
                                    scored-s))))
              [results (vec parsed-tokens)]))

          bad-morphemes (->> scored-sentences
                             (r/filter #(or (:register-score %) (:stats %) #_(and (:stats %) #_(> (-> % :stats :mi) 5.0))))
                             (into []))]
      {:results bad-morphemes :parsed-tokens parsed-tokens})
    (throw (Exception. (pr-str text)))))
