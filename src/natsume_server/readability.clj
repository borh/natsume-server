(ns natsume-server.readability
  (:require [clojure.java.shell :as shell]
            [clojure.string :as string]
            [clojure.java.io :as io]

            [incanter.stats :as stats]
            [plumbing.graph :as graph]
            [plumbing.core :refer [defnk fnk map-vals]]

            [natsume-server.annotation-middleware :as am]
            [natsume-server.collocations :as collocations]
            [natsume-server.models.db :as db]
            [natsume-server.text :as txt]
            [natsume-server.utils.xz :refer [xz-line-seq]]

            [taoensso.timbre :as log]
            [natsume-server.log-config :as lc]))

(lc/setup-log log/config :error)

;; # Features related to readability.
;;
;; The features can be roughly separated into three categories:
;;
;; 1. Syntax-level features
;; 2. Vocabulary-level features
;; 3. Surface-level features
;;
;; ## Syntax-level features
(defn chunk-depth
  "Dependency grammar chunk depth.
  Differentiates flat and heavily nested(?) sentences.
  Higher values indicate greater complexity.
  Should be averaged by number of chunks."
  [t]
  (loop [chunks t
         depth-stack [0]]
    (let [last-depth (peek depth-stack)
          chunk (first chunks)]
      (if (= -1 (:link chunk))
        (apply + depth-stack)
        #_(/ (reduce + depth-stack) (count depth-stack))
        (recur (next chunks)
               (if (> (- (:link chunk) (:id chunk)) 1)
                 (conj depth-stack (inc last-depth))
                 (conj depth-stack last-depth)))))))

(defn link-distance
  "Average dependency link distance between nodes.
  Higher values indicate greater complexity (with respect to reading (working) memory load).
  Should be averaged by number of chunks - 1."
  [t]
  (if (< (count t) 2)
    0
    (let [chunks (drop-last t)] ; we drop last chunk as it has no link
      (apply + (for [chunk chunks] (- (:link chunk) (:id chunk))))
      #_(/
         (reduce +
                 (for [chunk chunks] (- (:link chunk) (:id chunk))))
         (count chunks)))))

(defn subordinate-clause-count
  [t])

;; ## Vocabulary-level features
;;
;; The BCCWJ and adaptive average word log frequencies both depend on
;; pre-computed frequencies, so one pass of the copora must have
;; already been done.
;; The average JLPT word level can be done based on the 4 JLPT word
;; level lists already available.
;; In the future, these should be generalized so that log() is
;; optional for the functions that only need to act on one morpheme at
;; a time.
(defonce BCCWJ-word-map
  (let [lines (xz-line-seq "data/BCCWJ-LB-pos-lemma-map.tsv.xz")]
    (reduce (fn [BCCWJ-map line]
              (let [[pos1 lemma freq-string] (string/split line #"\t")
                    freq (Integer/parseInt freq-string)]
                (assoc-in BCCWJ-map [pos1 lemma] freq)))
            {}
            lines)))

(defn BCCWJ-word-log-freq
  "Word log frequency in corpus (/Balanced Corpus of Contemporary Written Japanese/).
  Greater numbers correspond with lesser difficulty.
  TODO: smoothing (although log is OK for now).
  Should be averaged by morphemes."
  [t]
  (let [tokens (am/tree-to-morphemes-flat t)]
    (if (zero? (count tokens))
      0.0
      (->> tokens
           (map #(select-keys % [:pos-1 :lemma]))
           (map #(get-in BCCWJ-word-map [(:pos-1 %) (:lemma %)] 0)) ; value for missing calculated using Good-Turing
           (map (partial + 1))
           (map #(Math/log10 %))
           (apply +)))))

(defn adaptive-word-log-freq
  "Adaptive word log frequency based on the users writing purpose (which is then mapped to corpora (distances))."
  [t corpus])

(defonce JLPT-word-map
  (let [lines (xz-line-seq "data/JLPT-Word-List.tsv.xz")]
    (reduce (fn [JLPT-map line]
              (let [[orth pron string-level] (map string/trim
                                                  (string/split (string/replace line #"～" "")
                                                                #"\t"))
                    level (Integer/parseInt string-level)]
                (assoc JLPT-map orth level pron level)))
            {}
            lines)))

(defn JLPT-word-level
  "(pre-2010) Japanese Language Proficiency Test word level.
  Greater numbers correspond with lesser difficulty.

  TODO decide if we want to check based on orthBase or lemma -> we do both now.
  Should be averaged by morphemes."
  [t]
  ;; filter away word classes that are not represented in the JLPT word lists
  ;; TODO whitelist
  (let [content-tokens (filter
                        (fn [x] (re-seq #"(名詞|形容詞|動詞|副詞|形状詞|接頭辞|助動詞|接尾辞)" (:pos-1 x)))
                        (am/tree-to-morphemes-flat t))
        content-tokens-count (count content-tokens)]
    (float
     (if (zero? content-tokens-count)
       0                              ; or 1?
       (/
        (->> content-tokens
             (map #(select-keys % [:orth-base :lemma]))
             (map vals)
             (map #(select-keys JLPT-word-map %))
             (map (fn [x] (let [xval (vals x)] (if (nil? xval) '(0) xval))))
             (map #(apply max %))
             (apply +))
        (count content-tokens) 4)))))

(defn collocation-avg-log-freq
  "Average collocation log frequency in corpus."
  [t])

(defn obi2level
  [s]
  (let [obi-output (:out (shell/sh "obi2" :in s))
        split-output (string/split obi-output #"\s")]
    (Integer/parseInt (first split-output))))

(def memoized-obi2level
  (memoize obi2level))

;; ## Surface-level features
;;
;; Token, chunk and predicate counts operate on the CaboCha tree
;; structure of a sentence.
(defn token-count
  "Morpheme count （形態素数）."
  [t]
  (count (am/tree-to-morphemes-flat t)))

(defn chunk-count
  "Chunk count （文節数）."
  [t]
  (count t))

(defn predicate-count
  "Predicate count （述語数）.
  A predicate is either a verb, adjective or copula with :c-form of '終止形-一般'."
  [t]
  (reduce
   (fn [predicates chunk]
     (if (some #(= (:c-form %) "終止形-一般") (:tokens chunk))
       (inc predicates)
       predicates))
   0
   t))

;; ### Shibasaki's predicate count
;; 1. 出現した全部の動詞（ただし、複合動詞は１としてカウントする）
;; 2. 「形容詞＋名詞」（例：赤い花）の形で出現しない形容詞（例：空は青く，山は緑だ．父の手は大きい．）({:cType 連用形-一般} {:pos-1 読点})
;; 3. 「形容動詞＋名詞」（例：偉大な仕事）の形で出現しない形容動詞（その男は正直で、誠実だった．）
;; 4. 「名詞＋判定詞」（例：明日は良い天気でしょう．これは母の鏡だ．次は渋谷ですか．（判定詞＝助動詞）
;; 5. 「名詞＋句点」すなわち体言止め（例：空からふる白いものは雪．）(i.e. last token is noun or noun + period)
;; 6. 「非自立名詞＋助動詞」（例：のだ，のです）
;;
;; BUG: 文四郎はためらわずにその指を口に含むと、傷口を強く吸った。 => 3 predicates!? (ためらわずに is verb?!)
(defn predicate-count-shibasaki
  "Predicate count （平均述語数）."
  [t]
  (reduce
   (fn [predicates chunk]
     (if (some
          (fn [token]
            (or
             (= (:c-form token) "終止形-一般")
             (= (:pos-1  token) "動詞")
             (and (=    (:pos-1 token) "助動詞")
                  (not= (:orth token) "な")
                  (re-seq #"(だ|です)" (:lemma token)))
             (= (:pos-2  token) "終助詞")
             (= (:pos-2  token) "句点")))
          (:tokens chunk))
       (inc predicates)
       predicates))
   0
   t))

;; NOTE: counts by writing system operate on the raw string.
(defn char-writing-system
  "Return writing system type of char.
  To get codepoint of char: `(.codePointAt char 0)`.
  Converting code point into char: `(char 0x3041)`."
  [^String ch]
  (let [code-point (.codePointAt ch 0)]
    (cond
     (and (>= code-point 0x3041) (<= code-point 0x309f)) :hiragana
     (and (>= code-point 0x4e00) (<= code-point 0x9fff)) :kanji
     (and (>= code-point 0x30a0) (<= code-point 0x30ff)) :katakana
     (or (and (>= code-point 65)    (<= code-point 122))    ; half-width alphabet (A-Za-z)
         (and (>= code-point 65313) (<= code-point 65370))  ; full-width alphabet (Ａ-Ｚａ-ｚ)
         (and (>= code-point 48)    (<= code-point 57))     ; half-width numbers  (0-9)
         (and (>= code-point 65296) (<= code-point 65305))) ; full-width numbers  (０-９)
     :romaji
     (or (= code-point 12289) (= code-point 65291) (= code-point 44)) :commas ; [、，,] <- CHECK
     :else :symbols)))

#_(defn writing-system-count
    "Returns a hash-map of character frequencies by writing system, averaging counts based on string length.
  TODO get counts from the number of matches in re-seq, i.e.: (count (re-seq regexp string))."
    [s]
    (let [l (float (.length s))
          target-keys [:hiragana :kanji :katakana :romaji :symbols]
          freq-map (frequencies (map char-writing-system (map str s)))]
      (assoc (apply hash-map
                    (flatten (for [k target-keys]
                               (list k (/ (get freq-map k 0.0) l)))))
        :commas (get freq-map :commas 0)))) ; :commas are not averaged


(defn writing-system-count
  "Returns a hash-map of character frequencies by writing system."
  [s]
  (merge {:hiragana 0
          :kanji    0
          :katakana 0
          :romaji   0
          :symbols  0
          :commas   0}
         (frequencies (map char-writing-system (map str s)))))

(def goshu-rename-map
  {"和"   :japanese
   "漢"   :chinese
   "外"   :gairai
   "記号" :symbolic
   "混"   :mixed
   "不明" :unk
   "固"   :pn})

(defn goshu-map
  "Should this instead by all inserted into an SQL array? ... would
  lower the number of columns dramatically."
  [t]
  (merge
   {:japanese 0
    :chinese  0
    :gairai   0
    :symbolic 0
    :mixed    0
    :unk      0
    :pn       0}
   (->> t
        am/tree-to-morphemes-flat
        (map :goshu)
        (map #(get goshu-rename-map %))
        frequencies)))

;; ### Shibasaki formula
;;
;; - year = 学年 (this is the return value of the function)
;; - hiragana-ratio = 平仮名の割合
;; - characters = 文の平均文字数
;; - chunks = 文の平均文節数
;; - predicates = 文の平均述語数
(defn shibasaki
  "Taken from Shibasaki et al. (2011) p. 225; returns the K-12 year of input text."
  [^long hiragana ^long characters ^long chunks ^long predicates] ;; TODO predicates not used in formula #1
  (let [hiragana-ratio (/ hiragana characters)]
    (+ -147.9
       (* 3.601E-4 hiragana-ratio)
       (- (* 8.772E-2 hiragana-ratio hiragana-ratio))
       (* 6.651 hiragana-ratio)
       (* 3.679 chunks)
       (* 3.142E-4 hiragana-ratio hiragana-ratio characters)
       (- (* 3.986E-4 hiragana-ratio hiragana-ratio chunks))
       (- (* 3.207E-4 hiragana-ratio characters characters))
       (- (* 3.109E-2 hiragana-ratio characters))
       (- (* 7.968E-3 hiragana-ratio chunks chunks))
       (* 3.468E-3 hiragana-ratio characters chunks))))

;; ### Tateishi's formula
;;
;; RS = 0.06 x pa + 0.25 x ph - 0.19 x pc - 0.61 x pk
;;      -1.34 x Is -1.35 x la + 7.52 x lh - 22.1 x lc - 5.3 x lk
;;      -3.87 x cp - 109.1
;;
;; Tateishi's formula: RS = -0.12ls - 1.37la + 7.4lh - 23.18lc - 5.4lk - 4.67cp + 115.79
;;
;; pa, ph, pc, pk are the percentages of alphabet runs, hiragana runs, kanzi runs, and katakana runs, respectively:
;;
;; - ls = average number of characters per sentence
;; - la = average number of Roman letters and symbols per run
;; - lh = average number of Hiragana characters per run
;; - lc = average number of Kanji characters per run
;; - lk = average number of Katakana characters per run
;; - cp = ratio of touten (comma) to kuten (period)
(defn get-runs
  [s]
  (let [r- (reduce
            (fn [a ch]
              (let [last-ch (first (:last a))
                    current- (char-writing-system ch)
                    current (if (or (= :symbols current-) (= :commas current-)) :romaji current-)]
                (if (= last-ch current)
                  (update-in a [:last] conj current)
                  (if (nil? last-ch) (assoc a :last [current])
                      (assoc a :last [current] last-ch (conj (get a last-ch) (count (:last a))))))))
            {:last     []
             :hiragana []
             :kanji    []
             :katakana []
             :romaji   []}
            (map str s))
        last-keys (:last r-)
        last-key  (first last-keys)
        last-keys-count (count last-keys)
        current-last-keys-vector (get r- last-key)
        r (dissoc (assoc r- last-key (conj current-last-keys-vector last-keys-count)) :last)
        total-runs (apply + (for [[_ v] r] (count v)))]
    ;;(pprint r)
    ;;(pprint last-keys)
    ;;(pprint current-last-keys-vector)
    ;;(println total-runs)
    (list (into {} (for [[k v] r] [k (* 100 (/ (count v) total-runs))]))
          (into {} (for [[k v] r] [k (if (zero? (count v)) 0.0 (float (/ (apply + v) (count v))))])))))

(defn tateishi
  ""
  [characters commas periods p-runs a-runs] ;romaji symbols hiragana katakana kanji
  (+ (*  0.06 (:romaji   p-runs))
     (*  0.25 (:hiragana p-runs))
     (* -0.19 (:kanji    p-runs))
     (* -0.61 (:katakana p-runs))
     (* -1.34 characters)
     (* -1.35 (:romaji   a-runs)) ; includes symbols
     (*  7.52 (:hiragana a-runs))
     (* 22.10 (:kanji    a-runs))
     (*  5.30 (:katakana a-runs))
     ;; should :periods realy be :sentences? -> divide-by-zero danger??
     (* -3.87 (/ commas periods))
     -109.1)
  #_(+ (*  -0.12 characters)
       (*  -1.37 (:romaji   a-runs)) ; includes symbols
       (*   7.4  (:hiragana a-runs))
       (* -23.18 (:kanji    a-runs))
       (*  -5.4  (:katakana a-runs))
       ;; should :periods realy be :sentences? -> divide-by-zero danger??
       (*  -4.67 (/ commas periods))
       115.79))

;; # Readability tables
;;
;; The problem WRT normalization is that there are several different normalizations required overall.
;; For example, the comma-period ratio is based on whole-text averaging????
;; !!There is a difference between averages of sentence averages and averages of whole texts.!!
;; Would be best to not average at this level -- leave it for the upper levels, maybe even in SQL.

(def core-sentence-data
  {;:tree         (fnk [sentence] (am/sentence->tree sentence))
   :tokens       (fnk [tree] (token-count tree))
   :chunks       (fnk [tree] (chunk-count tree))
   :predicates   (fnk [tree] (predicate-count-shibasaki tree))
   :collocations (fnk [tree] (collocations/extract-collocations tree))})

(def readability-stats
  {:jlpt-level   (fnk [tree] (JLPT-word-level tree))
   :bccwj-level  (fnk [tree] (BCCWJ-word-log-freq tree))
   :link-dist    (fnk [tree] (link-distance tree))
   :chunk-depth  (fnk [tree] (chunk-depth tree))})

(def char-type-stats
  {:length     (fnk [sentence] (count sentence))
   :char-types (fnk [sentence length] (map-vals #(float (/ % length)) (writing-system-count sentence)))})

(def goshu-stats
  {:goshu-map (fnk [tree tokens] (map-vals #(float (/ % tokens)) (goshu-map tree)))})

(def sentence-stats
  (merge core-sentence-data
         readability-stats
         char-type-stats
         goshu-stats))

(def sentence-graph
  (graph/eager-compile sentence-stats))

;; Based on: http://blog.jayfields.com/2010/09/clojure-flatten-keys.html
(defn flatten-keys
  "Flattens nested maps on inner keys."
  [m]
  (letfn [(flatten-keys* [a ks m]
            (if (map? m)
              (reduce into (map (fn [[k v]] (flatten-keys* a k v)) (seq m)))
              (assoc a ks m)))]
    (flatten-keys* {} [] m)))

(defn sentence-readability [tree s]
  (assoc (flatten-keys (sentence-graph {:tree tree :sentence s}))
    :text s))

#_(def average-readability-graph
  (assoc readability-graph
    :tokens      (by-sentences tokens)
    :chunks      (by-sentences chunks)
    :predicates  (by-sentences predicates)

    ;;:obi2_level  #_0 (if (empty? text) 0 (obi2level text)); text -> (db/get-paragraph-text paragraph-id)
    :tateishi    (tateishi avg-length commas sentences percentage-runs average-runs)
    :shibasaki   (shibasaki avg-hiragana avg-length avg-chunks avg-predicates)

    :jlpt_level  (by-sentences (:jlpt_level m)) #_(/ (:jlpt_level  m) tokens)
    :bccwj_level (/ (:bccwj_level m) tokens)

    :link_dist   (/ (:link_dist   m) (if (> 1 chunks) (dec chunks) chunks))
    :chunk_depth (/ (:chunk_depth m) chunks)))

(defn get-sentence-info
  [s]
  (let [tree         (am/sentence->tree s)
        collocations (collocations/extract-collocations tree)
        wsm        (writing-system-count s)
        gm         (goshu-map tree)
        characters (count s)
        chunks     (chunk-count tree)
        predicates (predicate-count-shibasaki tree)]
    #_(update-pos-lemma-freq tree @db/current-genres-id)
    (merge
     wsm
     gm
     {;;:collocations collocations
      :length      characters
      :tokens      (token-count tree)
      :chunks      chunks
      :predicates  predicates
      :jlpt_level  (float (JLPT-word-level tree))
      :bccwj_level (float (BCCWJ-word-log-freq tree))
      :link_dist   (float (link-distance tree))
      :chunk_depth (float (chunk-depth tree))})))

(defn average-readability
  [m text]
  (let [length     (:length   m)

        hiragana   (:hiragana m)
        katakana   (:katakana m)
        kanji      (:kanji    m)
        romaji     (:romaji   m)
        symbols    (:symbols  m)

        commas     (:commas   m)

        tokens     (:tokens   m)
        chunks     (:chunks   m); (if (> (:chunks m) 1) (:chunks m) 1)
        predicates (:predicates m)

        sentences (if (contains? m :sentences) (:sentences m) 1)

        by-sentences #(float (/ % sentences))
        by-length    #(float (/ % length))
        by-tokens    #(float (/ % tokens))

        avg-chunks     (by-sentences chunks)
        avg-length     (by-sentences length)
        avg-predicates (by-sentences predicates)
        avg-hiragana   (by-sentences hiragana)

        [percentage-runs average-runs] (get-runs text)
        ]
    ;;(log/debug text)
    (merge
     m
     {:hiragana    (by-length hiragana)
      :katakana    (by-length katakana)
      :kanji       (by-length kanji)
      :romaji      (by-length romaji)
      :symbols     (by-length symbols)

      :japanese    (by-tokens (:japanese m))
      :chinese     (by-tokens (:chinese  m))
      :gairai      (by-tokens (:gairai   m))
      :symbolic    (by-tokens (:symbolic m))
      :mixed       (by-tokens (:mixed    m))
      :unk         (by-tokens (:unk      m))
      :pn          (by-tokens (:pn       m))

      :commas      (by-sentences commas)

      :tokens      (by-sentences tokens)
      :chunks      (by-sentences chunks)
      :predicates  (by-sentences predicates)

      ;;:obi2_level  #_0 (if (empty? text) 0 (obi2level text)); text -> (db/get-paragraph-text paragraph-id)
      :tateishi    (tateishi avg-length commas sentences percentage-runs average-runs)
      :shibasaki   (shibasaki avg-hiragana avg-length avg-chunks avg-predicates)

      :jlpt_level  (by-sentences (:jlpt_level m)) #_(/ (:jlpt_level  m) tokens)
      :bccwj_level (/ (:bccwj_level m) tokens)

      :link_dist   (/ (:link_dist   m) (if (> 1 chunks) (dec chunks) chunks))
      :chunk_depth (/ (:chunk_depth m) chunks)})))

(defn text-readability
  [t]
  (let [sentences (vec (flatten (txt/lines->paragraph-sentences t)))
        readability-map (assoc (apply merge-with + (map get-sentence-info sentences))
                          :sentences (count sentences))]
    (assoc (average-readability readability-map t)
      :obi2_level (if (empty? t) 0 (memoized-obi2level t)))))


;; # TODO
;;
;; - Check out https://github.com/r0man/svm-clj for making classifier using which we can sort example sentences
;; - move from average to cummulative scores and normalize at a later step:
;;     - for chunk depth and link distance, divide by chunk count.
;;     - for JLPT and BCCWJ levels, divide by morpheme count, etc...
;; - JLPT-kanji-level (<- based on list)
;; - BCCWJ-LB-kanji-level (or rather corpus/facet based)
;; - CEFR-based difficulty (cf. `hagoromo`)
