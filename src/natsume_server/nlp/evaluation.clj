(ns natsume-server.nlp.evaluation
  (:require [schema.core :as s]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]

            [natsume-server.endpoint.api :as api]))

;; TODO go over adverb list and use error API to evaluate algo
(s/defschema Adverb {:adverb s/Str :category s/Str})
(s/defschema ScoredAdverb (assoc Adverb :score (s/enum :NA :good :bad)))
(s/defn get-adverbs :- [Adverb]
  [adverb-file :- s/Str]
  (->> (with-open [adverb-reader (io/reader adverb-file)]
         (doall (csv/read-csv adverb-reader :separator \tab :quote 0)))
       (zipmap [:adverb :category])
       (into [])))

(s/defn score-adverbs :- [ScoredAdverb]
  [conn :- s/Any
   adverbs :- [Adverb]]
  (for [{:keys [adverb] :as a} adverbs]
    (api/get-text-register conn {:body adverb})))           ;; FIXME any way of optimizing the parameters of the scoring function?

;; TODO precision/recall