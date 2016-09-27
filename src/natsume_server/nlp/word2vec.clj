(ns natsume-server.nlp.word2vec
  (:require [schema.core :as s]
            [plumbing.core :refer [for-map]]
            [clojure.java.io :as io]
            [byte-streams :refer [convert]]
            [natsume-server.nlp.importers.local :as local]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [mount.core :refer [defstate]]
            [natsume-server.config :refer [config]])
  (:import [org.deeplearning4j.models.word2vec.wordstore.inmemory InMemoryLookupCache]
           [org.deeplearning4j.text.sentenceiterator LineSentenceIterator BasicLineIterator]
           [org.deeplearning4j.text.tokenization.tokenizerfactory TokenizerFactory DefaultTokenizerFactory]
           [org.deeplearning4j.models.word2vec.Word2Vec$Builder]
           [org.deeplearning4j.models.word2vec Word2Vec]
           [org.deeplearning4j.models.embeddings.loader WordVectorSerializer]))

(hugsql/def-db-fns "natsume_server/nlp/sql/utils.sql")

(defn iterate-lines []
  (convert (for [token-sentence (token-stream connection)] (str/join " " (:string_agg token-sentence))) java.io.InputStream))

;; '/tmp/natsume_temp_corpus.txt'

(defn export-corpus!
  [unit-type features]
  (export-corpus connection
                 {:table (name unit-type) ; :unigrams/:tokens
                  :features (first (map name features)) ; FIXME made real vector (:orth)
                  :export-path (format "%s/corpus-%s-%s.txt"
                                       (System/getProperty "user.dir")
                                       (name unit-type)
                                       (str/join "_" (map name features)))}))

(s/defn train :- Word2Vec
  [corpus-path :- s/Str]
  (let [;;cache (doto
        ;;        (InMemoryLookupCache.)
        ;;        ;;(.lr 2e5)
        ;;        ;;(.vectorLength 100)
        ;;        ;;(.build)
        ;;        )
        ^Word2Vec v
        (->
         (org.deeplearning4j.models.word2vec.Word2Vec$Builder.)
         ;;(.sampling 1e-5)
         (.minWordFrequency 2)
         ;;(.batchSize 5000)
         ;;(.vocabCache cache)
         (.windowSize 10)
         (.layerSize 100)
         (.iterations 1)
         (.iterate #_(BasicLineIterator. (iterate-lines)) (LineSentenceIterator. (io/file corpus-path)))
         (.tokenizerFactory (DefaultTokenizerFactory.))
         (.build))]
    (println "Setup finished, fitting...")
    (doto v
      ;;(.setCache cache)
      (.fit))))

(s/defn save-model!
  [model :- Word2Vec save-path :- s/Str]
  (WordVectorSerializer/writeFullModel model save-path))

(s/defn load-model :- Word2Vec
  [load-path :- s/Str]
  (WordVectorSerializer/loadFullModel load-path))

(s/defn load-or-train! :- Word2Vec
  [unit-type features]
  (let [unit-type (or unit-type :suw)
        features (or features (case unit-type :suw [:orth] :unigrams [:string]))
        model-path (format "word2vec-model-%s-%s.model.bin"
                           (name unit-type)
                           (str/join "_" (map name features)))]
    (if (fs/exists? model-path)
      (load-model model-path)
      (let [corpus-path (format "%s/corpus-%s-%s.txt"
                                (System/getProperty "user.dir")
                                (name unit-type)
                                (str/join "_" (map name features)))
            model (train corpus-path)]
        (save-model! model model-path)
        model))))

(defstate !word2vec-models :start
  (for-map [{:keys [unit-type features] :as m} (:word2vec config)]
      m (load-or-train! unit-type features)))

;; Wrapper for functions defined in http://deeplearning4j.org/doc/org/deeplearning4j/models/embeddings/wordvectors/WordVectorsImpl.html

(defn similarity
  [unit-type features token-a token-b]
  (.similarity ^Word2Vec ({:unit-type unit-type :features features}
                          @!word2vec-models)
               token-a
               token-b))

(defn nearest-tokens ;; TODO .wordsNearestSum
  [unit-type features token n]
  (.wordsNearest ^Word2Vec ({:unit-type unit-type :features features}
                            @!word2vec-models)
                 ^String token
                 (int n)))

(defn similar-tokens-with-accuracy
  [unit-type features token accuracy]
  (.similarWordsInVocabTo ^Word2Vec ({:unit-type unit-type :features features}
                                     @!word2vec-models)
                          token
                          accuracy))

(comment
  (def t (train))
  (println "Getting similarity:")
  (.similarity t "日本" "中国")
  (.wordsNearest t "日本" 10)

  (let [unit-type :suw
        token-features [:orth]
        path (format "word2vec-model-%s-%s.model.bin"
                     (name unit-type)
                     (str/join "_" (map name token-features)))
        model (train)]
    (save-model! model path)
    (== model (load-model path))))
