(ns natsume-server.nlp.topic-model
  (:require [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clojure.string :as str]
            [schema.core :as s]
            [plumbing.core :refer [for-map]]
            [mount.core :refer [defstate]]
            [natsume-server.config :refer [config]]
            [natsume-server.nlp.importers.local :as local]
            [marcliberatore.mallet-lda :refer [make-instance-list lda]]
            [me.raynes.fs :as fs])
  (:import (cc.mallet.topics ParallelTopicModel)))

(s/defn load-or-create-model! :- ParallelTopicModel
  [unit-type :- (s/enum :suw :unigrams)
   features :- [s/Keyword]]
  (let [model-filename (format "%s/corpus-documents-%s-%s.topic.model.bin"
                               (System/getProperty "user.dir")
                               (name unit-type)
                               (str/join "_" (map name features)))
        #_corpus-filename #_(format "%s/corpus-documents-%s-%s.csv"
                                (System/getProperty "user.dir")
                                (name unit-type)
                                (str/join "_" (map name features)))]
    (if (fs/exists? model-filename)
      (ParallelTopicModel/read (io/as-file model-filename))
      (let [model
            (lda ;; alpha = 0.5, beta = 0.01
             (->> #_(with-open [reader (io/reader corpus-filename)]
                    (doall (csv/read-csv reader :separator \tab :quote 0)))
                  #_(map (fn [[basename genre tokens-string]]
                           [basename (str/split tokens-string #"\s")]))
                  (local/stream-corpus unit-type features)
                  (map (fn [{:keys [basename genre text]}]
                         [basename (str/split text #"\s")]))
                  make-instance-list)
             :num-topics 100
             :num-iter 1000
             :optimize-interval 10
             :optimize-burn-in 200
             :random-seed 0)]
        (.write ^ParallelTopicModel model (io/file model-filename))
        model))))

(defstate ^{:on-reload :noop}
  !topic-models :start
  (for-map [{:keys [unit-type features] :as m} (:topic-models config)]
      m (load-or-create-model! unit-type features)))

(defn get-probability [^ParallelTopicModel model document]
  (let [inferencer (.getInferencer model)]
    (.getSampledDistribution inferencer document 0 0 0)))

(defn get-instance [text]
  (first (make-instance-list [["user_text" (str/split text #"\s")]])))

(defn make-prediction
  "Infers the top topics for given text and returns the topics with their top words (default: 5 and 5)."
  ([unit-type features text]
   (make-prediction unit-type features text 5 5))
  ([unit-type features text n-topics n-words]
   (let [model ^ParallelTopicModel (get !topic-models {:unit-type unit-type :features features})
         probabilities (->> text
                            get-instance
                            (get-probability model))
         top-words (->> (.getTopWords model n-words)
                        (seq)
                        (mapv #(seq %)))]
     (->> (map vector (range (count probabilities)) probabilities top-words)
          (sort-by second)
          (reverse)
          (take n-topics)
          (mapv (fn [[topic-id prob tokens]]
                  {:id topic-id
                   :prob prob
                   :tokens tokens}))))))

;; TODO topic-document NNS functionality
;; https://www.postgresql.org/message-id/52687CEA.9060903@xavvy.com
