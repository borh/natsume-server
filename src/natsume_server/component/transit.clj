(ns natsume-server.component.transit
  (:require [taoensso.timbre :as timbre :refer [info]]
            [clojure.string :as str]
            [natsume-server.component.database :as db]
            [natsume-server.component.query :as q]
            [natsume-server.nlp.word2vec :as word2vec]
            [natsume-server.nlp.annotation-middleware :as anno]
            [natsume-server.nlp.topic-model :as topic-model]
            [natsume-server.nlp.error :as error]))

(defn with-time-duration
  "Returns a map wrapping the result in :result and time elapsed in :duration (in seconds)."
  [expr]
  (let [start (. System (nanoTime))
        result (expr)]
    {:duration (/ (double (- (. System (nanoTime)) start)) 1000000000.0)
     :result   result}))

;; API

(defn process-request [event data]
  (case event
    :server/ping true
    :sources/genre (data db/!norm-map)
    :fulltext/matches (q/query-fulltext db/connection data)
    :sources/sentence-id (q/query-expanded-document db/connection data)
    :sentences/collocations (q/query-sentences data)
    :sentences/tokens (q/query-sentences-tokens data)
    :tokens/tree (q/get-one-search-token data)
    :tokens/similarity (apply word2vec/similarity data)
    :tokens/nearest-tokens (apply word2vec/token-nearest-tokens data)
    :tokens/similarity-with-accuracy (apply word2vec/similar-tokens-with-accuracy data)
    :tokens/tsne (let [m-fn (memoize (fn [x] (apply word2vec/tsne x)))]
                   (m-fn data))
    :collocations/collocations (q/query-collocations data)
    :collocations/tree (q/query-collocations-tree data)
    :errors/register (error/get-error db/connection data)
    :suggestions/tokens (->> (db/q {:select    [:orth-base :pos-1]
                                    :modifiers [:distinct]
                                    :from      [:search-tokens]
                                    :group-by  [:morpheme/lemma :orth-base :pos-1]
                                    :where     [:= :morpheme/lemma (:morpheme/lemma data)]})
                             (map (fn [m]
                                    (assoc m :score
                                             (->> m
                                                  q/get-one-search-token
                                                  (error/sigma-score :default-pos 1)
                                                  :register-score
                                                  :good))))
                             (sort-by :score >))
    :topics/infer (let [text-tokens (->> (:text data)
                                         (anno/sentence->cabocha)
                                         (mapcat :chunk/tokens)
                                         (map (first (:features data))) ;; TODO Also, unit-type.
                                         (str/join " "))]
                    (topic-model/make-prediction (:unit-type data) (:features data) text-tokens))
    {:unknown-event {:event event :data data}}))