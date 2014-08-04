(ns natsume-server.lm
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.core.reducers :as r]
            [me.raynes.fs :as fs]
            [plumbing.core :refer :all]
            [incanter.stats :as stats]
            [natsume-server.models.db :as db]
            [fast-zip.core :as z]
            [d3-compat-tree.tree :as tree]
            [natsume-server.utils.naming :as naming]
            [natsume-server.config :as cfg])
  (:import [com.aliasi.lm LanguageModel NGramProcessLM CompiledNGramProcessLM TokenizedLM #_LanguageModel$Tokenized]
           [com.aliasi.tokenizer RegExTokenizerFactory]
           [java.io ObjectOutputStream FileOutputStream ObjectInputStream FileInputStream]))

;; TODO lazy-loading (and unloading) of already compiled models
;; TODO think hard on how we want to approach model building, evaluation and collocation extraction
;; TODO random forests: http://www.stat.berkeley.edu/~breiman/RandomForests/cc_home.htm http://code.google.com/p/fast-random-forest/
;; TODO SVM (http://liblinear.bwaldvogel.de/ or Weka/etc.)
;; TODO caret or Mahout/Weka? (rather both, caret for exploration and Mahout/Weka for production)
;; TODO make a nice export function so we can feed data to R/python etc. -- or -- use SQL from R to stream data in
;; TODO of course, all other ML methods assume some other feature set than an n-gram LM
;; TODO this is really a baseline (general) method for getting similarities between documents/registers
;; TODO token models taking token lemma conjoined with spaces. We can then use: TokenizerFactory factory = new RegExTokenizerFactory("\\S+");
;; TODO token n-gram with content words replaced by POS, leaving just functional words

;; ## N-Gram Model Building, Training and Evaluation

(defn build-char-model!
  [& {:keys [n num-chars lambda-factor]
      :or {n 3 num-chars 4000 lambda-factor 6.0}}]
  (NGramProcessLM. n num-chars lambda-factor))

(defn build-token-model!
  [& {:keys [n lambda-factor]
      :or {n 3 lambda-factor 6.0}}]
  (let [factory (RegExTokenizerFactory. "\\S+")]
    (TokenizedLM. factory n)))

(defn train!
  "Add text to n-gram model."
  [model text] ;; ^NGramProcessLM
  (.handle model text)) ; Or `.train`.

(defn eval-on-model
  [model text & {:keys [perplexity]}] ;; Smaller perplexity means better model. ; ^CompiledNGramProcessLM
  (let [log2estimate (if-not (empty? text)
                       (/ (.log2Estimate model text)
                          (count text))
                       0.0)]
    (if perplexity
      (Math/pow 2.0 (- log2estimate))
      log2estimate)))

;; TODO   TokenizedLM:
;; TODO   SortedSet<ScoredObject<String[]>> 	newTermSet(int nGram, int minCount, int maxReturned, LanguageModel.Tokenized backgroundLM)
;;        Returns a list of scored n-grams ordered by the significance of the degree to which their counts in this model exceed their expected counts in a specified background model.

;; ## Persistence

;; FIXME move from level/name model to X.Y.Z.K? filename -- ie. just the tree-map, maybe even using the tree function in models.db

(defn compile-model! [model-name model]
  (with-open [out (-> (str "data/models/" model-name ".model")
                      io/file
                      FileOutputStream.
                      ObjectOutputStream.)]
    (.compileTo model out))) ;; ^NGramProcessLM

(defn deserialize-model!
  ([fn] (with-open [in (-> fn
                           io/file
                           FileInputStream.
                           ObjectInputStream.)]
          (.readObject in))))

;; TODO move to new ltree model (need to replace most of the bunk save/load functions)
;; TODO need a way to remove duplicates: X.Y.Z.K + X.Y.Z -> X.Y.Z.K where K is the only child of X.Y.Z
;; Only run once all models have been made, and only when needed.
(defn- expand-seq [sequence]
  (loop [r  '()
         xs (reverse sequence)]
    (if (seq xs)
      (recur (conj r (reverse xs))
             (next xs))
      r)))
(defn- expand-seq-distinct [sequence]
  (distinct (map vec (mapcat expand-seq sequence))))

(defonce all-models
  ;; FIXME type and n as parameter
  (let [model-files (fs/glob "data/models/*.model")
        model-names (->> model-files
                         (map #(-> %
                                   fs/base-name
                                   (string/replace #"-.+" "")))
                         (map #(string/split % #"\.")))]
    (zipmap model-names (map deserialize-model! model-files))))

;; FIXME we are making too many models, ex.: parents with only one child are same data!
(defn eval-on-all-models [text]
  (println (count text))
  (let [ks (keys all-models)]
    (zipmap ks (pmap #(let [m (get all-models %)]
                        (->> text
                             (map (fn [line] (eval-on-model m line :perplexity true)))
                             incanter.stats/mean))
                     ks))))

(defn save-similarity-table! [sims & {:keys [type n] :or {type :char n 3}}]
  (spit (str "data/" (name type) "-" n "-computed-lm-similarities.clj") (pr-str sims))
  #_(with-open [out-file (io/writer "lm-similarity.tsv")]
      (let [header (mapcat keys (vals sims))]
        (clojure.data.csv/write-csv out-file [(mapv #(string/join "." %) header)] :separator \tab) ; header
        (doseq [line (mapv vals (vals sims))]
          (clojure.data.csv/write-csv out-file [line] :separator \tab)))))

(defn get-genre-lm-similarities [& {:keys [type] :or {type :char}}]
  (let [genres (expand-seq-distinct (map natsume-server.models.sql-helpers/ltree->seq (db/get-genres)))]
    (for-map [source-genre genres]
        source-genre (->> source-genre
                          db/sources-ids-by-genre
                          (mapcat (case type :char db/sources-text :token db/sources-tokens))
                          eval-on-all-models))))

(defn- parse-fn [fn]
  (let [match (re-seq #"^(.+)-(\d)" (fs/base-name fn))
        [type n] (-> match first rest)]
    [(keyword type) (Integer/parseInt n)]))
;; TODO init function
(case (cfg/opt :models :mode)
  :noop  (declare genre-lm-similarities) ; To fix compile.
  :build (defonce genre-lm-similarities (get-genre-lm-similarities)) ; WARNING computationally expensive, should persist.
  :load  (if-let [fn (fs/normalized (format "data/%s-%d-computed-lm-similarities.clj" ;; fs/normalized-path deprecated!
                                            (name (cfg/opt :models :n-gram :type))
                                            (cfg/opt :models :n-gram :n)))]
           (defonce genre-lm-similarities (read-string (slurp fn)))))

(defn- train-char-model [model model-name genre n & {:keys [full-model]}]
  (->> genre
       db/sentences-by-genre
       ((comp dorun map) #(train! model %)))
  (compile-model! model-name model)
  #_(let [model-name (str (string/join "." genre) "-char-" n "-gram")
          model (build-char-model!)]))
(defn- train-token-model [model model-name genre n & {:keys [full-model]}]
  (->> genre
       (#(db/tokens-by-genre % :field "orth"))
       ((comp dorun map) #(train! model %)))
  (compile-model! model-name model)
  #_(let [model-name (str (string/join "." genre) "-token-" n "-gram")
          model (if full-model
                  (build-token-model! :background-model full-model))]))

(defn compile-all-models! [type & {:keys [n] :or {n 3}}]
  (let [genres (expand-seq-distinct (map natsume-server.models.sql-helpers/ltree->seq (db/get-genres)))
        #_full-model #_(case type
                     :char (build-char-model! :n n)
                     :token (build-token-model! :n n))] ; Add all data to be used as background smoothing model for token n-grams.
    (doseq [genre genres]
      (let [model-name (str (string/join "." genre) "-" (name type) "-" n "-gram")
            model (case type
                    :char (build-char-model! :n n)
                    :token (build-token-model! :n n))]
        (case type
          :char (train-char-model model model-name genre n #_:full-model #_full-model)
          :token (train-token-model model model-name genre n #_:full-model #_full-model))))))

(comment
  (do
    (compile-all-models! :token)
    (save-similarity-table! (get-genre-lm-similarities :type :token) :type :token)))

;; ## Genre similarities API

; Binary operators
(defn dot
  "Returns the value of dot product of the vectors v1 and v2"
  [v1 v2]
  (reduce + (map * v1 v2)))

; Scalar operators
(defn scale
  "Returns the vector that is m times the vector v"
  [m v]
  (map #(* m %) v))

; Unary operators
(defn norm
  "Returns the (Euclidean) length of the vector v"
  [v]
  (Math/sqrt (dot v v)))

(defn normalise
  "Returns a vector of unit length in the same direction as v"
  [v]
  (scale (/ 1 (norm v)) v))

(defn normalise-polar
  "Returns a vector of unit length in the same direction as v"
  [v]
  (map #(* (- 0.5 %) 2) (normalise v)))

(defn make-score
  [v]
  (let [max-v (apply max v)
        min-v (apply min v)
        span (- max-v min-v)
        factor (/ span 2)]
    (map #(* 2 (- 0.5 (/ (- % min-v) span))) v)))

(defn traverse-zipper [tree]
  (loop [loc (tree/tree-zipper tree)
         r []]
    (if (z/end? loc)
      r
      (recur (z/next loc)
             (conj r ((juxt :name :count) (z/node loc)))))))

(defn get-genre-similarities-all [genre]
  (let [values (get genre-lm-similarities genre)
        values (let [k (keys values) v (vals values)] (zipmap k (make-score v)))
        similarities (remove #(= genre (first %)) values)]
    (tree/seq-to-tree
     (reduce (fn [init [k v]]
               (conj init {:genre k :count v}))
             []
             similarities)
     :root-values {:count 0.0}
     :merge-fns {:count #(if %1 %1 %2)})))
(def stjc-similarities (delay (get-genre-similarities-all ["科学技術論文"])))
(defn similarity-score [pos tree]
  (let [computed-tree (tree/normalize-tree @stjc-similarities tree :boost-factor 1 :update-field :count :update-fn *)]
    computed-tree))

(comment
  (similarity-score :a (db/get-one-search-token {:orth-base "て" :lemma "て"} :compact-numbers false))
  (->> (similarity-score :a (db/get-one-search-token {:orth-base "て" :lemma "て"} :compact-numbers false)) traverse-zipper (r/filter #(neg? (second %))) (into []))
  (->> (similarity-score :a (db/get-one-search-token {:orth-base "て" :lemma "て"} :compact-numbers false)) traverse-zipper (r/remove #(= "Genres" (first %))) (r/map second) (r/reduce +))
  )


(defn get-genre-similarities [genre]
  (let [values (get genre-lm-similarities genre)
        values (let [k (keys values) v (vals values)] (zipmap k (make-score v)))
        root-value #_1.0 (some #(if (= genre (first %)) (second %)) values)
        similarities (remove #(= genre (first %)) values)]
    (tree/seq-to-tree
     (reduce (fn [init [k v]]
               (conj init {:genre k :similarity v}))
             []
             similarities)
     :root-name genre
     :root-values {:similarity root-value}
     :merge-fns {:similarity #(if %1 %1 %2)})))

;; FIXME now how to map these scores on a (-1,1) scale????? in fact, all other genres are negative, just some more than others. The question is, again, to what does -1 correspond to. With perplexity, we are looking at the average branching factor.

(comment
  (use 'incanter.stats)
  (use 'incanter.charts)
  (require 'incanter.core)
  (incanter.core/view (histogram (vals (remove #(< 3000 (second %)) (vals genre-lm-similarities)))))
  (incanter.core/view (histogram (remove #(< 1000 %) (map second (mapcat val genre-lm-similarities))) :nbins 100)))
