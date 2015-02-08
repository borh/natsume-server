(ns natsume-server.nlp.evaluation
  (:require [schema.core :as s]
            [plumbing.core :refer [map-keys]]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clojure.core.match :refer [match]]
            [clojure.core.reducers :as r]

            [dk.ative.docjure.spreadsheet :as spreadsheet]

            [natsume-server.component.database :as db]
            [natsume-server.nlp.cabocha-wrapper :refer [recode-pos]]
            [natsume-server.utils.xz :refer [xz-reader]]
            [natsume-server.nlp.error :as error]))

(s/defschema Token
             {:orth-base s/Str :lemma s/Str :pos-1 s/Str :pos s/Keyword
              :academic-written (s/maybe s/Bool) :academic-written-n (s/maybe s/Bool) :normal-written (s/maybe s/Bool)
              :public-spoken (s/maybe s/Bool) :normal-spoken (s/maybe s/Bool)})

(s/defschema ScoredToken
             (assoc Token :academic-score (s/maybe s/Bool) :colloquial-score (s/maybe s/Bool)))

(def test-data "data/unidic-adverb-test-data.tsv.xz")

(s/defn get-tokens :- [Token]
  [test-file :- s/Str]
  (->> (with-open [test-reader (xz-reader test-file)]
         (doall (csv/read-csv test-reader :separator \tab :quote 0)))
       (r/drop 1)
       (r/map (fn [[表層形 左文脈ID 右文脈ID コスト 品詞大分類 品詞中分類 品詞小分類 品詞細分類 活用型 活用形 語彙素読み 語彙素 書字形出現形 発音形出現形 書字形基本形 発音形基本形 語種 語頭変化型 語頭変化形 語末変化型 語末変化形 アカデミックな書き言葉 一般的な書き言葉 公的な話し言葉 日常の話し言葉 備考]]
                ;; We only need a few features to match.
                ;; FIXME How to handle "？"?
                {:orth-base 書字形出現形
                 :lemma 語彙素
                 :pos-1 品詞大分類
                 :pos :adverb #_(recode-pos 品詞大分類)
                 :academic-written (case アカデミックな書き言葉   "○" true "×" false nil)
                 :academic-written-n (case アカデミックな書き言葉   "○" false "×" true nil)
                 :normal-written   (case 一般的な書き言葉      "○" true "×" false nil)
                 :public-spoken    (case 公的な話し言葉       "○" true "×" false nil)
                 :normal-spoken    (case 日常の話し言葉       "○" true "×" false nil)}))
       (r/remove (fn [{:keys [academic-written normal-written public-spoken normal-spoken]}]
                   (and (nil? academic-written) (nil? normal-written)
                        (nil? public-spoken) (nil? normal-spoken))))
       #_(r/filter (fn [{:keys [academic-written]}]
                   (false? academic-written)))
       (into [])))

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
           (let [{:keys [good bad]}
                 (:register-score (error/token-register-score conn token))

                 score (if (and good bad) ;; FIXME double-check
                         (let [diff (Math/abs (- good bad))]
                           (cond (and (pos? good) (neg? bad) (>= diff threshold)) true
                                 (and (neg? good) (pos? bad) (>= diff threshold)) false
                                 :else nil)))]
             ;;(println good bad score)
             (assoc token
                    :academic-score   (case score true true  false false nil #_nil false)
                    :colloquial-score (case score true false false true  nil #_nil false)))))
       #_(r/remove (fn [{:keys [academic-score colloquial-score]}]
                   (and (nil? academic-score) (nil? colloquial-score))))
       (into []))) ;; FIXME any way of optimizing the parameters of the scoring function?

(s/defn extend-tokens-information
  [tokens :- [Token]
   threshold :- s/Num]
  (->> tokens
       (r/map
         (fn [token]
           (let [{:keys [verdict mean chisq raw-freqs freqs]} (:register-score (error/token-register-score conn token))
                 rename (fn [s k] (-> k name (str s) keyword))
                 total-freq (reduce + 0 (vals raw-freqs))]
             (merge (select-keys token [:orth-base :lemma])
                    (map-keys (partial rename "-norm-freq") freqs)
                    (map-keys (partial rename "-freq") raw-freqs)
                    (map-keys (partial rename "-chisq") chisq)
                    {:verdict verdict
                     :mean mean
                     :total-freq total-freq
                     :total-norm-freq (* 1000000 (/ total-freq (-> @db/!norm-map :tokens :count)))}))))
       (into [])))

(comment
  (extend-tokens-information (get-tokens test-data) 0.0))

(s/defn save-excel-table!
  []
  (let [data (extend-tokens-information (get-tokens test-data) 0.0)
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
        default-header [:lemma :orth-base :verdict :total-freq :total-norm-freq :mean]
        norm-freq-corpora-header (mapv #(str % "-norm-freq") sorted-corpora)
        freq-corpora-header (mapv #(str % "-freq") sorted-corpora)
        chisq-corpora-header (mapv #(str % "-chisq") sorted-corpora)
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
    (spreadsheet/add-sheet! wb "合計")
    (let [totals-sheet (spreadsheet/select-sheet "合計" wb)
          corpora-counts (->> @db/!norm-map :tokens :children (map (juxt :name :count)) (into {}))
          corpora-header sorted-corpora #_(vec (keys corpora-counts))]
      (spreadsheet/add-rows! totals-sheet [(into ["全コーパス"] corpora-header)
                                           (into [(-> @db/!norm-map :tokens :count)]
                                                 (mapv (fn [k] (get corpora-counts k)) corpora-header))]))
    (spreadsheet/set-row-style! header-row (spreadsheet/create-cell-style! wb {:font {:bold true}}))
    (spreadsheet/save-workbook! "副詞リスト.xlsx" wb)))

(comment
  (score-tokens (get-tokens test-data))
  (filter #(and (:score %) (:normal-spoken %)) (score-tokens (get-tokens test-data))))

(s/defn save-table
  [fn :- s/Str
   tokens :- [ScoredToken]]
  (let [ks [:orth-base :lemma :pos-1
            :academic-written :academic-written-n :normal-written :public-spoken :normal-spoken
            :colloquial-score :academic-score]]
    (with-open [w (io/writer fn)]
      (csv/write-csv w (into [(mapv name ks)] (mapv #(mapv % ks) tokens)) :separator \tab :quote 1))))

(comment (save-table "unidic-adverb-scored-2014-12-22-1.tsv" (score-tokens (get-tokens test-data))))

(s/defschema ConfusionMatrix {:tp s/Num :fp s/Num :fn s/Num :tn s/Num :NA s/Num})

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

(s/defn precision :- s/Num
  [cm :- ConfusionMatrix]
  (let [{:keys [tp fp]} cm
        tp+fp (+ tp fp)]
    (if (pos? tp+fp)
      (double (/ tp (+ tp fp)))
      0.0)))

(s/defn recall :- s/Num
  [cm :- ConfusionMatrix]
  (let [{:keys [tp fn]} cm
        tp+fn (+ tp fn)]
    (if (pos? tp+fn)
      (double (/ tp (+ tp fn)))
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
      (let [cm (confusion-matrix (score-tokens (get-tokens test-data) t) :normal-spoken :colloquial-score)]
        [t (precision cm) (recall cm) cm]))))

(comment
  (precision-recall-curve 0.0 1000.0 10.0))

(comment
  (f1 (confusion-matrix (score-tokens (get-tokens test-data)) :normal-spoken :colloquial-score))
  (f1 (confusion-matrix (score-tokens (get-tokens test-data)) :academic-written :academic-score)))

(def variations
  [{:t :academic-written-n :p :colloquial-score}
   {:t :academic-written :p :academic-score}
   {:t :normal-written   :p :academic-score}
   {:t :public-spoken    :p :colloquial-score}
   {:t :normal-spoken    :p :colloquial-score}])

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
        (let [cm (confusion-matrix (score-tokens (get-tokens test-data) 100.0) t p)]
          (csv/write-csv w [[(str (name t) " : " (name p)) "" ""]
                            ["" "Test positive" "Test negative"]
                            ["Predicted positive" (:tp cm) (:fp cm)]
                            ["Predicted negative" (:fn cm) (:tn cm)]
                            [(str "NA = " (:NA cm) " N = " (reduce + (vals cm)))]]
                         :separator \tab :quote 1))))
    (with-open [w (io/writer (str fn "-scores.tsv"))]
      (csv/write-csv w (into [(mapv name score-keys)] (mapv #(mapv % score-keys) scores))
                     :separator \tab :quote 1))))

(comment
  (use 'clojure.pprint)
  (print-table (get-all-variations variations))
  (publish "results"))

(s/set-fn-validation! true)