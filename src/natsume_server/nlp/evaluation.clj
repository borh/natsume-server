(ns natsume-server.nlp.evaluation
  (:require [schema.core :as s]
            [plumbing.core :refer [map-keys map-vals ?>]]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clojure.core.match :refer [match]]
            [clojure.core.reducers :as r]

            [dk.ative.docjure.spreadsheet :as spreadsheet]

            [natsume-server.component.database :as db]
            [natsume-server.nlp.cabocha-wrapper :refer [recode-pos]]
            [natsume-server.utils.xz :refer [xz-reader]]
            [natsume-server.nlp.error :as error])
  (:import [com.ibm.icu.text Transliterator]))

(def romaji-transliterator (Transliterator/getInstance "Katakana-Latin;"))

(defn romanize
  [^String s]
  (string/replace (.transliterate ^Transliterator romaji-transliterator s) "~tsu" "t"))

(s/defschema Token
  {:orth-base   s/Str :morpheme/lemma s/Str :pos-1 s/Str :morpheme/pos s/Keyword :romaji s/Str :lemma-romaji s/Str :display-lemma s/Str
   :アカデミックな書き言葉 (s/maybe s/Bool) :アカデミックな書き言葉-n (s/maybe s/Bool) :一般的な書き言葉 (s/maybe s/Bool)
   :公的な話し言葉     (s/maybe s/Bool) :日常の話し言葉 (s/maybe s/Bool)})

(s/defschema ScoredToken
  (assoc Token :準正用判定 (s/maybe s/Bool) :準誤用判定 (s/maybe s/Bool)))

(def test-data "data/unidic-adverb-test-data.tsv.xz")

(s/defn get-tokens :- [Token]
  [test-file :- s/Str]
  (let [filter-map
        (reduce
         (fn [a [語彙素
                 書字形出現形
                 発音形出現形
                 アカデミックな書き言葉
                 一般的な書き言葉
                 公的な話し言葉
                 日常の話し言葉]]
           (assoc a
                  {:orth-base      書字形出現形
                   :morpheme/lemma 語彙素}
                  {:アカデミックな書き言葉   (case アカデミックな書き言葉 "○" true "×" false nil)
                   :アカデミックな書き言葉-n (case アカデミックな書き言葉 "○" false "×" true nil)
                   :一般的な書き言葉      (case 一般的な書き言葉 "○" true "×" false nil)
                   :公的な話し言葉       (case 公的な話し言葉 "○" true "×" false nil)
                   :日常の話し言葉       (case 日常の話し言葉 "○" true "×" false nil)}))
         {}
         (with-open [ratings-reader (xz-reader "data/unidic-adverbs-ratings-final.tsv.xz")]
           (doall (csv/read-csv ratings-reader :separator \tab :quote 0))))]
    (->>
     (with-open [test-reader (xz-reader test-file)]
       (doall (csv/read-csv test-reader :separator \tab :quote 0)))
     (sequence
      (comp
       (drop 1)
       (map (fn [[表層形 左文脈ID 右文脈ID コスト 品詞大分類 品詞中分類 品詞小分類 品詞細分類 活用型 活用形 語彙素読み 語彙素 書字形出現形 発音形出現形 書字形基本形 発音形基本形 語種 語頭変化型 語頭変化形 語末変化型 語末変化形 アカデミックな書き言葉 一般的な書き言葉 公的な話し言葉 日常の話し言葉 備考]]
              ;; We only need a few features to match.
              ;; FIXME How to handle "？" in annotation?
              {:orth-base      書字形出現形
               :morpheme/lemma 語彙素
               :pos-1          品詞大分類
               :morpheme/pos   :adverb #_(recode-pos 品詞大分類)
               :romaji         (romanize 発音形基本形)
               :lemma-romaji   (romanize 語彙素読み)
               ;; Note that empty annotations do not necessarily mean anything, so we cannot use them as true/false values like we can with the system NA scores.
               :アカデミックな書き言葉    (case アカデミックな書き言葉 "○" true "×" false nil)
               :アカデミックな書き言葉-n  (case アカデミックな書き言葉 "○" false "×" true nil)
               :一般的な書き言葉       (case 一般的な書き言葉 "○" true "×" false nil)
               :公的な話し言葉        (case 公的な話し言葉 "○" true "×" false nil)
               :日常の話し言葉        (case 日常の話し言葉 "○" true "×" false nil)}))
       ;; FIXME Care needs to be taken when interpreting overall frequenices based on this list, because the distinct here does not just look at orth-base and lemma values but also annotations which are outside the system
       (distinct)
       (filter (fn [{:keys [orth-base morpheme/lemma]}]
                 (get filter-map {:orth-base orth-base :morpheme/lemma lemma} false)))
       (map (fn [{:keys [orth-base morpheme/lemma] :as m}]
              (let [changed (merge m (get filter-map {:orth-base orth-base :morpheme/lemma lemma}))]
                #_(if (not= changed m)
                    (println "Change:" m changed)) ;; shirashira/shirajira
                changed)))))
     (reduce
      (fn [a m]
        (let [uniq-ident ((juxt :morpheme/lemma :orth-base) m)]
          (if (get a uniq-ident)
            (let [diff (clojure.set/difference (:lemma-romaji (get a uniq-ident)) #{(:lemma-romaji m)})
                  union (clojure.set/union (:lemma-romaji (get a uniq-ident)) #{(:lemma-romaji m)})]
              #_(clojure.pprint/pprint {:union union
                                        :diff diff
                                        :m ((juxt :orth-base :morpheme/lemma :romaji :lemma-romaji) m)
                                        :a ((juxt :orth-base :morpheme/lemma :romaji :lemma-romaji) (get a uniq-ident))})
              (-> a
                  (update-in [uniq-ident :romaji]
                             (fn [r] (conj r (:romaji m))))
                  (update-in [uniq-ident :lemma-romaji]
                             (fn [r] (conj r (:lemma-romaji m))))))
            (assoc a uniq-ident (-> m
                                    (update :romaji (fn [r] #{r}))
                                    (update :lemma-romaji (fn [r] #{r})))))))
      {})
     ((fn [a]
        (let [lemma-romaji-map
              (reduce
               (fn [d [[lemma _] {:keys [lemma-romaji]}]]
                 (update d lemma (fn [l] (into (if l l #{}) lemma-romaji))))
               {}
               a)]
          (map-vals
           (fn [m] (-> m
                       (update :romaji (fn [r] (clojure.string/join "/" r)))
                       (update :lemma-romaji (fn [r] (clojure.string/join "/" r)))
                       (assoc :display-lemma (str (:morpheme/lemma m) " /" (clojure.string/join "/" (get lemma-romaji-map (:morpheme/lemma m))) "/"))))
           a))))
     vals)))

(def conn (db/druid-pool {:subname "//localhost:5432/natsumedev"
                          :user "natsumedev"
                          :password "riDJMq98LpyWgB7F"}))

;; TODO add JLPT levels using JLPT-word-map from nlp.readability ns.

(s/defn score-tokens :- [ScoredToken]
  [;;conn :- s/Any
   tokens :- [Token]
   threshold :- s/Num]
  (->> tokens
       (r/map
         (fn [token]
           (let [{:keys [good bad verdict]}
                 (:register-score (error/token-register-score conn (select-keys token [:orth-base :morpheme/lemma :pos-1])))

                 score (if (and good bad)
                         (let [diff (Math/abs ^Double (- ^Double good ^Double bad))]
                           (cond (and (>= good 0.0) (neg? bad) (>= diff threshold)) true
                                 (and (<= good 0.0) (pos? bad) (>= diff threshold)) false
                                 :else nil)))]
             (if (not= score verdict)
               (println score verdict token))
             (assoc token
                    ;; The choice of how to handle nil values is made using the following logic: when measuring the precision/recall of our system, a nil score is akin to a false (i.e. this is not wrong) score. We cannot make the same assumption about the test-set classification, as the nil vales have no inherent meaning there.
                    :score score
                    :score-verdict verdict
                    :準正用判定 (case score true true  false false nil #_nil false)
                    :準誤用判定 (case score true false false true  nil #_nil false)))))
       #_(r/remove (fn [{:keys [academic-score colloquial-score]}]
                   (and (nil? academic-score) (nil? colloquial-score))))
       (into []))) ;; FIXME any way of optimizing the parameters of the scoring function?

(s/defn rename-corpus :- s/Keyword
  [s :- s/Str
   k :- (s/either s/Keyword s/Str)]
  (-> k name (str s) keyword))

(s/defn extend-tokens-information
  [tokens :- [Token]]
  (->> tokens
       (r/map
         (fn [token]
           (let [{:keys [verdict mean chisq raw-freqs freqs]} (:register-score (error/token-register-score conn (select-keys token [:orth-base :morpheme/lemma :pos-1])))
                 total-freq (reduce + 0 (vals raw-freqs))]
             (merge (select-keys token [:orth-base :morpheme/lemma :romaji :pos-1 :display-lemma :アカデミックな書き言葉 :一般的な書き言葉 :公的な話し言葉 :日常の話し言葉])
                    (map-keys (partial rename-corpus "-出現割合") freqs)
                    (map-keys (partial rename-corpus "-頻度") raw-freqs)
                    (map-keys (partial rename-corpus "-χ^2 検定の結果") chisq)
                    {:判定             verdict
                     :全コーパスにおける出現割合の平均 (or mean 0.0)
                     :全コーパスにおける頻度           total-freq
                     :All-PPM          (* 1000000 (/ total-freq (-> db/!norm-map :chunk/tokens :count)))
                     :Pos-PPM          (* (/ (reduce + (vals (select-keys raw-freqs ["白書" "科学技術論文" "法律"])))
                                             (reduce + (vals (select-keys db/!genre-tokens-map ["白書" "科学技術論文" "法律"]))))
                                          1000000)
                     :Neg-PPM          (* (/ (reduce + (vals (select-keys raw-freqs ["Yahoo_知恵袋" "Yahoo_ブログ" "国会会議録"])))
                                             (reduce + (vals (select-keys db/!genre-tokens-map ["Yahoo_知恵袋" "Yahoo_ブログ" "国会会議録"]))))
                                          1000000)}))))
       (into [])))

(comment
  (extend-tokens-information (get-tokens test-data)))

(comment
  (score-tokens (get-tokens test-data) 0.0)
  (filter #(and (:score %) (:日常の話し言葉 %)) (score-tokens (get-tokens test-data) 0.0)))

(s/defn save-table
  [fn :- s/Str
   tokens :- [ScoredToken]]
  (let [ks [:orth-base :morpheme/lemma :pos-1 :romaji :display-lemma
            :アカデミックな書き言葉 :アカデミックな書き言葉-n :一般的な書き言葉 :公的な話し言葉 :日常の話し言葉
            :準誤用判定 :準正用判定]]
    (with-open [w (io/writer fn)]
      (csv/write-csv w (into [(mapv name ks)] (mapv #(mapv % ks) tokens)) :separator \tab :quote 1))))

(comment (save-table "unidic-adverb-scored-2014-12-22-1.tsv" (score-tokens (get-tokens test-data) 0.0)))

(def opt s/optional-key)
(s/defschema ConfusionMatrix {:tp s/Num :fp s/Num :fn s/Num :tn s/Num
                              (opt :xt) s/Num (opt :xf) s/Num (opt :tx) s/Num (opt :fx) s/Num
                              (opt :NA) s/Num (opt :xx) s/Num})
(s/defschema ConfusionMatrixWithNA {:tp s/Num :fp s/Num :fn s/Num :tn s/Num})

(s/defn confusion-matrix :- ConfusionMatrix
  [tokens :- [ScoredToken]
   true-field :- s/Keyword
   predicted-field :- s/Keyword]
  "Calculates the confusion matrix from tokens given the true and predicted fields."
  (r/reduce
    (fn [cm token]
      (let [true-val (get token true-field)
            predicted-val (get token predicted-field)]
        (update cm
                (match [true-val predicted-val]
                       [true  true] :tp [false  true] :fp
                       [true false] :fn [false false] :tn
                       :else :NA)
                inc)))
    {:tp 0 :fp 0 :fn 0 :tn 0 :NA 0}
    tokens))

(s/defn confusion-matrix-with-na :- ConfusionMatrix
  [tokens :- [ScoredToken]
   true-field :- s/Keyword
   predicted-field :- s/Keyword]
  "Calculates the confusion matrix from tokens given the true and predicted fields, but also considers NA values."
  (r/reduce
    (fn [cm token]
      (let [true-val (get token true-field)
            predicted-val (get token predicted-field)]
        (update cm
                (match [true-val predicted-val]
                       [true  true] :tp [false  true] :fp
                       [true false] :fn [false false] :tn
                       ;; NA variations below:
                       [true  nil] :tx [false nil] :fx
                       [nil false] :xf [nil  true] :xt
                       [nil   nil] :xx)
                inc)))
    {:tp 0 :fp 0 :fn 0 :tn 0
     :tx 0 :fx 0 :xf 0 :xt 0
     :xx 0}
    tokens))

(s/defn precision :- s/Num
  [cm :- ConfusionMatrix]
  (let [{:keys [tp fp]} cm
        tp+fp (+ tp fp)]
    (if (pos? tp+fp)
      (double (/ tp tp+fp))
      0.0)))

(s/defn recall :- s/Num
  [cm :- ConfusionMatrix]
  (let [{:keys [tp fn]} cm
        tp+fn (+ tp fn)]
    (if (pos? tp+fn)
      (double (/ tp tp+fn))
      0.0)))

(s/defn f1 :- s/Num
  [cm :- ConfusionMatrix]
  (/ (* 2.0 (precision cm) (recall cm))
     (+ (precision cm) (recall cm))))

(s/defn f05 :- s/Num
  [cm :- ConfusionMatrix]
  (/ (* (+ 1 (* 0.5 0.5)) (precision cm) (recall cm))
     (+ (* (precision cm) 0.5 0.5) (recall cm))))

(s/defn precision-recall-curve :- [[s/Num]]
  "Vary the difference in freq. between corpora and see precision/recall."
  [min :- s/Num
   max :- s/Num
   step :- s/Num]
  (vec
    (for [t (range min max step)]
      (let [cm (confusion-matrix (score-tokens (get-tokens test-data) t) :日常の話し言葉 :準誤用判定)]
        [t (precision cm) (recall cm) cm]))))

(comment
  (precision-recall-curve 0.0 1000.0 10.0))

(comment
  (f1 (confusion-matrix (score-tokens (get-tokens test-data) 0.0) :日常の話し言葉 :準誤用判定))
  (f1 (confusion-matrix (score-tokens (get-tokens test-data) 0.0) :アカデミックな書き言葉 :準正用判定)))

(def variations
  [{:t :アカデミックな書き言葉-n :p :準誤用判定}
   {:t :アカデミックな書き言葉   :p :準正用判定}
   {:t :一般的な書き言葉         :p :準正用判定}
   {:t :公的な話し言葉           :p :準誤用判定}
   {:t :日常の話し言葉           :p :準誤用判定}])

(s/defn get-all-variations
  [true-predicted :- [{:t s/Keyword :p s/Keyword}]]
  (for [{:keys [t p]} true-predicted]
    (let [cm (confusion-matrix (score-tokens (get-tokens test-data) 100.0) t p)]
      {:true t
       :predicted p
       :precision (precision cm)
       :recall (recall cm)
       :f1 (f1 cm)})))

(s/defn publish
  [fn :- s/Str]
  (let [scores (get-all-variations variations)
        score-keys [:true :predicted :precision :recall :f1]]
    (with-open [w (io/writer (str fn "-cm.tsv"))]
      (doseq [{:keys [t p]} variations]
        (let [cm (confusion-matrix (score-tokens (get-tokens test-data) 0.0) t p)]
          (csv/write-csv w [[(str (name t) " : " (name p)) "" ""]
                            ["" "Test positive" "Test negative"]
                            ["Predicted positive" (:tp cm) (:fp cm)]
                            ["Predicted negative" (:fn cm) (:tn cm)]
                            [(str "NA = " (:NA cm) " N = " (reduce + (vals cm)))]]
                         :separator \tab :quote 1))))
    (with-open [w (io/writer (str fn "-scores.tsv"))]
      (csv/write-csv w (into [(mapv name score-keys)] (mapv #(mapv % score-keys) scores))
                     :separator \tab :quote 1))))

(s/defn save-excel-table!
  []
  (let [tokens (get-tokens test-data)
        data (extend-tokens-information tokens)
        data (score-tokens data 0.0)
        sorted-corpora ["科学技術論文"
                        "白書"
                        "法律"
                        "検定教科書"
                        "広報紙"
                        "新聞"
                        "書籍"
                        "雑誌"
                        "Yahoo_知恵袋"
                        "Yahoo_ブログ"
                        "韻文"
                        "国会会議録"]
        default-header [:morpheme/lemma :orth-base :romaji :display-lemma :pos-1 :アカデミックな書き言葉 :一般的な書き言葉 :公的な話し言葉 :日常の話し言葉 :判定 :score-verdict :score :準誤用判定 :準正用判定 :All-PPM :Pos-PPM :Neg-PPM :全コーパスにおける出現割合の平均 :全コーパスにおける頻度]
        norm-freq-corpora-header (mapv (partial rename-corpus "-出現割合") sorted-corpora)
        freq-corpora-header (mapv (partial rename-corpus "-頻度") sorted-corpora)
        chisq-corpora-header (mapv (partial rename-corpus "-χ^2 検定の結果") sorted-corpora)
        header (vec (concat default-header
                            freq-corpora-header
                            norm-freq-corpora-header
                            chisq-corpora-header)) #_(-> data first keys vec)
        wb (spreadsheet/create-workbook
             "副詞リスト"
             (into [(mapv name header)]
                   (mapv (fn [m]
                           (vec
                             (for [k header]
                               (let [v (get m k)]
                                 (cond
                                   (keyword? v) (name v)
                                   (true? v) 1
                                   (false? v) 0
                                   :else v)))))
                         data)))
        sheet (spreadsheet/select-sheet "副詞リスト" wb)
        header-row (first (spreadsheet/row-seq sheet))]
    (spreadsheet/set-row-style! header-row (spreadsheet/create-cell-style! wb {:font {:bold true}}))

    (spreadsheet/add-sheet! wb "合計")
    (let [totals-sheet (spreadsheet/select-sheet "合計" wb)
          corpora-counts (->> db/!norm-map :chunk/tokens :children (map (juxt :name :count)) (into {}))
          corpora-header sorted-corpora #_(vec (keys corpora-counts))]
      (spreadsheet/add-rows! totals-sheet [(into ["χ^2(α=0.1)" "全コーパス"] corpora-header)
                                           (into [0.0 (-> db/!norm-map :chunk/tokens :count)]
                                                 (mapv (fn [k] (get corpora-counts k)) corpora-header))])
      (let [totals-header-row (first (spreadsheet/row-seq totals-sheet))]
        (spreadsheet/set-row-style! totals-header-row (spreadsheet/create-cell-style! wb {:font {:bold true}}))))

    (spreadsheet/add-sheet! wb "Precision-Recall")
    (let [pr-sheet (spreadsheet/select-sheet "Precision-Recall" wb)
          t :アカデミックな書き言葉-n
          p :準誤用判定
          cm (confusion-matrix-with-na (score-tokens tokens 0.0) t p)]
      (spreadsheet/add-rows! pr-sheet
                             [[(str (name t) " : " (name p)) "" "" ""]
                              ["" "Test positive" "Test negative" "Test NA"]
                              ["Predicted positive" (:tp cm) (:fp cm) (:tx cm)]
                              ["Predicted negative" (:fn cm) (:tn cm) (:fx cm)]
                              ["Predicted NA"       (:xt cm) (:xf cm) (:xx cm)]

                              ["" "" "" ""]

                              ["Precision"    "Recall"    "F1"    "F0.5"   "NA"     "N"]
                              [(precision cm) (recall cm) (f1 cm) (f05 cm) (reduce + ((juxt :xx :tx :fx :xt :xf) cm)) (reduce + (vals cm))]])
      (let [header-row (first (spreadsheet/row-seq pr-sheet))]
        (spreadsheet/set-row-style! header-row (spreadsheet/create-cell-style! wb {:font {:bold true}}))))


    (spreadsheet/save-workbook! "副詞リスト.xlsx" wb)))

(comment
  (use 'clojure.pprint)
  (print-table (get-all-variations variations))
  (publish "results"))

(s/set-fn-validation! true)
