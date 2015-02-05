(ns natsume-server.nlp.error
  (:require [schema.core :as s]
            [plumbing.core :refer [?> ?>> map-vals]]
            [natsume-server.nlp.text :as text]
            [natsume-server.nlp.annotation-middleware :as anno]
            [natsume-server.component.database :as db]
            [natsume-server.nlp.collocations :as coll]
            [natsume-server.nlp.stats :as stats]
            [natsume-server.utils.numbers :refer [compact-number]]
            [clojure.core.reducers :as r]
            [clojure.set :as set]))

(defn sigma-score [pos n tree]
  (if-let [freqs (->> tree :children (map (juxt :name :count)) (into {}) seq)]
    (let [freqs (let [diff (- (count @db/!genre-names) (count freqs))]
                  (if (pos? diff)
                    (concat freqs (seq (zipmap (set/difference @db/!genre-names (set (map first freqs))) (repeat diff 0.0))))
                    freqs))
          mean (stats/mean (vals freqs))
          ;;sd (stats/sd (vals freqs))
          ;; df = 11 (BCCWJ + STJC + Wikipedia?)
          ;; 0.10      	0.05 	0.025 	0.01 	0.005
          ;; 17.275 	19.675 	21.920 	24.725 	26.757
          chisq-line (case pos
                       :noun 26.757
                       :verb 26.757
                       :particle 26.757
                       :adverb 17.275
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
                            :frequencies freqs
                            :verdict (cond
                                       (and (and good-sum (>= good-sum 0.0)) (and bad-sum (neg? bad-sum))) true
                                       (and (and good-sum (<= good-sum 0.0)) (and bad-sum (pos? bad-sum))) false
                                       :else nil)}
           :found? true}
          (?> (> n 1) (assoc :stats (map-vals compact-number (select-keys tree [:count :mi :t :llr]))))))
    {:found? false}))

(defn mi-score [conn collocation]
  ;; If no gram found in 1-gram DB, use 1 or 0.5 for freq.
  (let [n (count (:type-vector collocation))
        strings [:string-1 :string-2 :string-3 :string-4]
        ;; Get counts for each string-*, if possible.
        db-results (map (partial db/query-collocations-tree conn)
                        (for [i (take n (range (count strings)))]
                          (-> (apply dissoc collocation strings)
                              (assoc :measure #{:count :mi}
                                     :normalize? false
                                     :type (nth (:type-vector collocation) i)
                                     :string-1 ((nth strings i) collocation)))))
        freqs (->> db-results (map :count) (map #(if (or (nil? %) (zero? %)) 1.0 %)))
        f-xx (get @db/!tokens-by-gram n) #_(:count ((:type collocation) @!gram-totals)) ;; Count for all genres.
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
                                (mapcat (fn [part] (remove nil? [(:head-string part) (:tail-string part)]))
                                        (:data query)))
                        (assoc :compact-numbers false
                               :measure #{:count :mi :f-xi :f-ix}
                               :type-vector (:type query)
                               :type (->> (:type query)
                                          (map name)
                                          (clojure.string/join "-")
                                          keyword)))
        tree (db/query-collocations-tree conn collocation)
        n (count (:type-vector collocation))]
    (if (seq (:children tree))
      (sigma-score :collocation n tree)
      (if (> n 1)
        (mi-score conn collocation)
        -2))))

(s/defn token-register-score
  "Old formula, but includes measures other than chi-sq."
  [conn query]
  (let [results (db/get-one-search-token conn query :compact-numbers false)]
    (sigma-score (:pos query) 1 results)))

(s/defn score-sentence [conn tree sentence]
  (let [tokens (->> tree
                    (mapcat :tokens)
                    (remove (fn [{:keys [pos pos-1 pos-2]}] (or (= pos :symbol) (and (= pos-1 "助詞") (or (= pos-2 "格助詞") (= pos-2 "係助詞"))))))
                    (pmap (fn [m]
                            (let [register-score (token-register-score conn m)
                                  response {:type :token
                                            :tags (:tags m)
                                            :pos (:pos m)
                                            :begin (:begin m)
                                            :end (:end m)
                                            :lemma (:lemma m)
                                            :string (:orth m)}]
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
                                        {:type :collocation
                                         :pos (:type m)
                                         :tags (:tags m)
                                         :parts (->> m
                                                     :data
                                                     (r/map (fn [part]
                                                              (let [begin (or (:head-begin part) (:tail-begin part))
                                                                    end   (or (:head-end part)   (:tail-end part))
                                                                    pos   (or (:head-pos part)   (:tail-pos part))
                                                                    tags  (or (:head-tags part)  (:tail-tags part))
                                                                    lemma (or (:head-string part) (:tail-string part))]
                                                                {:begin begin
                                                                 :end end
                                                                 :pos pos
                                                                 :tags tags
                                                                 :lemma lemma
                                                                 :string (subs sentence begin end) #_(:head-string part) #_(:tail-string part)})))
                                                     (into []))}
                                        register-score (collocation-register-score conn m)]
                                    (-> record
                                        (assoc :string (clojure.string/join (map :string (:parts record))))
                                        (?> (map? register-score) (merge register-score))))))
                          (into []))]
    (concat tokens collocations)))

(s/defn get-error
        [conn :- s/Any
         text :- s/Str]
  (if-let [paragraphs (->> text vector text/lines->paragraph-sentences)]
    (let [update-positions (fn [m offset] (-> m (update-in [:begin] + offset) (update-in [:end] + offset)))

          [scored-sentences parsed-tokens]
          (loop [ss (for [paragraph paragraphs sentence paragraph] sentence)
                 offset 0
                 parsed-tokens []
                 results []]
            (if-let [s (first ss)]
              (let [tree (anno/sentence->tree s)
                    token-seq (mapv #(select-keys % [:orth :orth-base :lemma :pos-1 :pos-2 :c-form :c-type :tags]) (mapcat :tokens tree))
                    scored-s (score-sentence conn tree s)
                    length-s (count s)
                    new-offset (+ offset length-s)
                    nl? (and (< new-offset (count text)) (= \newline (first (subs text new-offset (inc new-offset)))))]
                (recur (next ss)
                       (+ new-offset (if nl? 1 0))
                       (concat parsed-tokens (if nl? (conj token-seq {:orth "\n" :orth-base "\n" :lemma "\n" :pos-1 "補助記号" :pos-2 "*" :c-form "*" :c-type "*"}) token-seq))
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