(ns natsume-server.nlp.word2vec
  (:require [schema.core :as s]
            [plumbing.core :refer [for-map]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            #_[byte-streams :refer [convert]]
            [datoteka.core :as fs]
            [clojure.data.csv :as csv]
            [mount.core :refer [defstate]]
            [natsume-server.config :refer [config]]
            [natsume-server.nlp.importers.local :as local]
            [clojure.string :as string]
            [taoensso.timbre :as timbre])
  (:import [org.nd4j.linalg.factory Nd4j]
           [org.nd4j.linalg.api.ndarray INDArray]
           [org.nd4j.linalg.indexing NDArrayIndex]
           [org.nd4j.linalg.dimensionalityreduction PCA]
           [org.deeplearning4j.plot BarnesHutTsne]
           [org.deeplearning4j.models.word2vec.wordstore.inmemory InMemoryLookupCache]
           [org.deeplearning4j.text.sentenceiterator LineSentenceIterator BasicLineIterator]
           [org.deeplearning4j.text.tokenization.tokenizerfactory TokenizerFactory DefaultTokenizerFactory]
           [org.deeplearning4j.models.word2vec Word2Vec$Builder]
           [org.deeplearning4j.models.word2vec Word2Vec]
           [org.deeplearning4j.models.embeddings.loader WordVectorSerializer]))

(defn export-corpus!
  [unit-type features]
  (spit
   (format "%s/models/corpus-%s-%s.txt"
           (System/getProperty "user.dir")
           (name unit-type)
           (str/join "_" (map name features)))
   (string/join "\n"
                (local/extract-tokens
                 (local/stream-corpus
                  unit-type
                  features)))))

(defn export-labeled-corpus!
  [unit-type features]
  (spit
   (format "%s/models/corpus-labeled-%s-%s.txt"
           (System/getProperty "user.dir")
           (name unit-type)
           (str/join "_" (map name features)))
   (string/join "\n"
                (->> (local/stream-corpus
                      unit-type
                      features)
                     (local/extract-labeled-tokens)
                     (map #(update % 0 (fn [l] (str "__label__" l))))
                     (map (partial string/join " "))))))

(s/defn train :- Word2Vec
  [corpus-path :- s/Str]
  (let [^Word2Vec v
        (->
         (org.deeplearning4j.models.word2vec.Word2Vec$Builder.)
         ;;(.sampling 1e-5)
         (.minWordFrequency 2)
         ;;(.batchSize 5000)
         ;;(.vocabCache cache)
         (.windowSize 8)
         (.layerSize 100)
         (.iterations 1)
         (.iterate #_(BasicLineIterator. (iterate-lines)) (LineSentenceIterator. (io/file corpus-path))) ;; FIXME Seq iterator for local/tokens-stream
         (.tokenizerFactory (DefaultTokenizerFactory.))
         (.build))]
    (when (:verbose config)
      (timbre/info "Fitting word2vec model..."))
    (doto v
      (.fit))))

(s/defn save-model!
  [model :- Word2Vec save-path :- s/Str]
  (WordVectorSerializer/writeWord2VecModel model save-path))

(s/defn load-model :- Word2Vec
  [load-path :- s/Str]
  (WordVectorSerializer/readWord2VecModel load-path))

(s/defn load-or-train! :- Word2Vec
  [unit-type features]
  (let [unit-type (or unit-type :suw)
        features (or features (case unit-type :suw [:morpheme/orth] :unigrams [:string]))
        model-path (format "models/word2vec-model-%s-%s.model.bin"
                           (name unit-type)
                           (str/join "_" (map name features)))]
    (if (fs/exists? model-path)
      (load-model model-path)
      (let [corpus-path (format "%s/models/corpus-%s-%s.txt"
                                (System/getProperty "user.dir")
                                (name unit-type)
                                (str/join "_" (map name features)))
            corpus-labeled-path (format "%s/models/corpus-labeled-%s-%s.txt"
                                        (System/getProperty "user.dir")
                                        (name unit-type)
                                        (str/join "_" (map name features)))
            _ (if-not (fs/exists? corpus-path)
                (export-corpus! unit-type features))
            _ (if-not (fs/exists? corpus-labeled-path)
                (export-labeled-corpus! unit-type features))
            model (train corpus-path)]
        (save-model! model model-path)
        model))))

(defstate ;; ^{:on-reload :noop}
  !word2vec-models :start
  (when (:server config)
    (for-map [{:keys [unit-type features] :as m} (:word2vec config)]
        m (load-or-train! unit-type features))))

;; Wrapper for functions defined in http://deeplearning4j.org/doc/org/deeplearning4j/models/embeddings/wordvectors/WordVectorsImpl.html

(defn similarity
  [unit-type features token-a token-b]
  (.similarity ^Word2Vec
               (get !word2vec-models
                    {:unit-type unit-type :features features})
               token-a
               token-b))

(defn token-nearest-tokens ;; TODO .wordsNearestSum
  [unit-type features token n]
  (.wordsNearest ^Word2Vec
                 (get !word2vec-models
                      {:unit-type unit-type :features features})
                 ^String token
                 (int n)))

(comment
  (defn tokens-nearest-tokens
    [unit-type features tokens n]
    (.wordsNearest ^Word2Vec
                   (get !word2vec-models
                        {:unit-type unit-type :features features})
                   (java.util.ArrayList. tokens) ;;
                   (int n))))

(defn similar-tokens-with-accuracy ;; FIXME
  [unit-type features token accuracy]
  (timbre/debug [unit-type features token accuracy])
  (.similarWordsInVocabTo ^Word2Vec
                          (get !word2vec-models
                               {:unit-type unit-type :features features})
                          token
                          accuracy))

(defn tokens-nearest-pos-neg-tokens
  [unit-type features positive-tokens negative-tokens n]
  (.wordsNearest ^Word2Vec
                 (get !word2vec-models
                      {:unit-type unit-type :features features})
                 positive-tokens ;; TEST: (java.util.ArrayList.)
                 negative-tokens
                 (int n)))

(defn get-token-vector
  [unit-type features token]
  (let [model
        ^Word2Vec(get !word2vec-models {:unit-type unit-type :features features})]
    (.getWordVectorMatrix model token)))

(defn pca
  [unit-type features n token]
  (let [nearest-tokens (token-nearest-tokens unit-type features token n)
        matrix ^INDArray (Nd4j/vstack
                          (for [token nearest-tokens]
                            (get-token-vector unit-type features token)))]
    (PCA/pca matrix 5 true) ;; PCA in Nd4j is currently broken
    ))

(defn ^BarnesHutTsne tsne-builder []
  (-> (org.deeplearning4j.plot.BarnesHutTsne$Builder.)
      (.setMaxIter 500)
      (.stopLyingIteration 250)
      (.learningRate 500)
      (.useAdaGrad false)
      (.theta 0.5)
      (.setMomentum 0.5)
      (.normalize true)
      (.build)))

(defn tsne
  #_([unit-type features] (tsne unit-type features 1000))
  #_([unit-type features n]
     (let [tsne (tsne-builder)
           model ^Word2Vec (get !word2vec-models {:unit-type unit-type :features features})]

       (-> model
           (.lookupTable)
           (.plotVocab tsne n (io/file "tsne-plot.csv")))

       (mapv (fn [[x y word]]
               {:x x :y y :word word})
             (with-open [r (io/reader "tsne-plot.csv")]
               (doall (csv/read-csv r :separator \, :quote 0))))))
  ([unit-type features token]
   (tsne unit-type features token 50 2))
  ([unit-type features token n-neighbours]
   (tsne unit-type features token n-neighbours 2))
  ([unit-type features token n-neighbours n-dims]
   (let [nearest-tokens (token-nearest-tokens unit-type features token n-neighbours)
         matrix ^INDArray (Nd4j/vstack
                           (for [token nearest-tokens]
                             (get-token-vector unit-type features token)))
         bht (tsne-builder)]

     (.fit bht ^INDArray matrix n-dims)

     (let [coords (.getData bht)
           rows (.rows coords)]
       (for [i (range rows)]
         (merge {:token (nth nearest-tokens i)}
                (zipmap [:x :y :z] (map #(.getDouble coords i %) (range n-dims)))))))))

;; INDArray matrix, int nDims
;; BarnesHutTsne.fit(matrix, nDims) -> Void
;; .Y -> output data



;; Nd4j.vstack


;; WeightLookupTable weightLookupTable = wordVectors.lookupTable();
;; Iterator<INDArray> vectors = weightLookupTable.vectors();
;; INDArray wordVector = wordVectors.getWordVectorMatrix("myword");
;; double[] wordVector = wordVectors.getWordVector("myword");


;; TODO https://github.com/deeplearning4j/dl4j-examples/blob/master/dl4j-examples/src/main/java/org/deeplearning4j/examples/nlp/paragraphvectors/ParagraphVectorsClassifierExample.java

(comment
  (def t (train))
  (println "Getting similarity:")
  (.similarity t "日本" "中国")
  (.wordsNearest t "日本" 10)

  (let [unit-type :suw
        token-features [:morpheme/orth]
        path (format "models/word2vec-model-%s-%s.model.bin"
                     (name unit-type)
                     (str/join "_" (map name token-features)))
        model (train)]
    (save-model! model path)
    (== model (load-model path))))
