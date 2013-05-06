(ns natsume-server.lm
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [plumbing.core :refer :all]
            [natsume-server.models.db :as db]
            [natsume-server.config :as cfg])
  (:import [com.aliasi.lm LanguageModel NGramProcessLM CompiledNGramProcessLM #_LanguageModel$Tokenized]
           [java.io ObjectOutputStream FileOutputStream ObjectInputStream FileInputStream]))

;; TODO lazy-loading (and unloading) of already compiled models
;; TODO think hard on how we want to approach model building, evaluation and collocation extraction
;; TODO random forests: http://www.stat.berkeley.edu/~breiman/RandomForests/cc_home.htm http://code.google.com/p/fast-random-forest/
;; TODO SVM
;; TODO caret or Mahout/Weka? (rather both, caret for exploration and Mahout/Weka for production)
;; TODO make a nice export function so we can feed data to R/python etc. -- or -- use SQL from R to stream data in
;; TODO of course, all other ML methods assume some other feature set than an n-gram LM
;; TODO this is really a baseline (general) method for getting similarities between documents/registers

;; ## N-Gram Model Building, Training and Evaluation

(defn build-char-model!
  [& {:keys [n num-chars lambda-factor]
      :or {n 3 num-chars 4000 lambda-factor 6.0}}]
  (NGramProcessLM. n num-chars lambda-factor))

(defn train!
  "Add text to n-gram model."
  [^NGramProcessLM model text]
  (.handle model text))

(defn eval-on-model
  [^CompiledNGramProcessLM model text & {:keys [perplexity]}] ;; Smaller perplexity means better model.
  (let [log2estimate (if-not (empty? text)
                       (/ (.log2Estimate model text)
                          (count text))
                       0.0)]
    (if perplexity
      (Math/pow 2.0 (- log2estimate))
      log2estimate)))

;; ## Persistence

;; FIXME move from level/name model to X.Y.Z.K? filename -- ie. just the tree-map, maybe even using the tree function in models.db

(defn compile-model! [model-name model]
  (with-open [out (-> (str "data/models/" model-name ".comp.lm.model")
                      io/file
                      FileOutputStream.
                      ObjectOutputStream.)]
    (.compileTo ^NGramProcessLM model out)))

(defn deserialize-model!
  ([fn] (with-open [in (-> fn
                           io/file
                           FileInputStream.
                           ObjectInputStream.)]
          (.readObject in)))
  ([model-name compiled?]
     (deserialize-model! (str "data/models/" model-name (if compiled? ".comp") ".lm.model"))))

;; TODO move to new ltree model (need to replace most of the bunk save/load functions)
;; TODO need a way to remove duplicates: X.Y.Z.K + X.Y.Z -> X.Y.Z.K where K is the only child of X.Y.Z
;; Only run once all models have been made, and only when needed.

(defonce all-models
  (let [model-files (fs/glob "data/models/*.model")
        model-names (->> model-files
                         (map #(-> %
                                   fs/base-name
                                   (string/replace #"\.(model|comp|lm)" "")))
                         (map #(string/split % #"\.")))]
    (zipmap model-names (map deserialize-model! model-files))))

(defn eval-on-all-models [text]
  (println (count text))
  (let [ks (keys all-models)]
    (zipmap ks (pmap #(eval-on-model (get all-models %) text :perplexity true)
                     ks))))

(defn get-genre-lm-similarities []
  (let [genres (db/get-genres)]
    (for-map [source-genre genres]
             source-genre (->> source-genre
                               db/sources-ids-by-genre
                               (map db/sources-text)
                               (apply str)
                               eval-on-all-models
                               #_(sort-by second)))))


(let [fn (fs/normalized-path "data/computed-lm-similarities.clj")]
  (if (cfg/opt :models :n-gram)
    (if (fs/exists? fn)
      (defonce genre-lm-similarities (map-vals #(sort-by first %) (read-string (slurp fn))))
      (defonce genre-lm-similarities (get-genre-lm-similarities))) ; WARNING computationally expensive, should persist.
    (def genre-lm-similarities)))

(defn save-similarity-table! [sims]
  (spit "data/computed-lm-similarities.clj" (pr-str sims))
  #_(with-open [out-file (io/writer "lm-similarity.tvs")]
      (let [header (mapcat keys (vals sims))]
        (clojure.data.csv/write-csv out-file [(mapv #(string/join "." %) header)] :separator \tab) ; header
        (doseq [line (mapv vals (vals sims))]
          (clojure.data.csv/write-csv out-file [line] :separator \tab)))))

(defn- expand-seq [sequence]
  (loop [r  '()
         xs (reverse sequence)]
    (if (seq xs)
      (recur (conj r (reverse xs))
             (next xs))
      r)))

(defn compile-all-models! []
  (let [genres (distinct (map vec (mapcat expand-seq (db/get-genres))))]
    (doseq [genre genres]
      (let [model-name (string/join "." genre)
            model (build-char-model!)]
        (->> genre
             db/sentences-by-genre
             ((comp dorun map) #(train! model %)))
        (compile-model! model-name model)))))

;; ## Genre similarities API

(defn get-genre-similarities [genre]
  (let [values (get genre-lm-similarities genre)
        root-value (some #(if (= genre (first %)) (second %)) values)
        similarities (remove #(= genre (first %)) values)]
    (db/seq-to-tree
     (reduce (fn [init [k v]]
               (conj init {:genre k :count v}))
             []
             similarities)
     :root-name genre
     :root-value root-value
     :merge-fn #(if %1 %1 %2))))

;; FIXME now how to map these scores on a (-1,1) scale????? in fact, all other genres are negative, just some more than others. The question is, again, to what does -1 correspond to. With perplexity, we are looking at the average branching factor.

(comment
  (use 'incanter.stats)
  (use 'incanter.charts)
  (require 'incanter.core)
  (incanter.core/view (histogram (vals (remove #(< 3000 (second %)) (vals genre-lm-similarities)))))
  (incanter.core/view (histogram (remove #(< 1000 %) (map second (mapcat val genre-lm-similarities))) :nbins 100)))
