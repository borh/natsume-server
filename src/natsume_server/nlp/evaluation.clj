(ns natsume-server.nlp.evaluation
  (:require [schema.core :as s]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clojure.core.match :refer [match]]
            [clojure.core.reducers :as r]

            [natsume-server.component.database :as db]
            [natsume-server.utils.xz :refer [xz-reader]]
            [natsume-server.nlp.error :as error]))

(s/defschema Token
             {:orth-base s/Str :lemma s/Str :pos-1 s/Str
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
   tokens :- [Token]]
  (->> tokens
       (r/map
         (fn [token] ;; There will be duplicates for lemmas, so going with orth (probably not good idea though, should just directly query database and not use MeCab/CaboCha at all....)
           (let [score (->> token
                            (error/token-register-score conn)
                            :register-score
                            :verdict)]
             (assoc token
                    :academic-score   (case score true true  false false nil #_nil false)
                    :colloquial-score (case score true false false true  nil #_nil false)))))
       #_(r/remove (fn [{:keys [academic-score colloquial-score]}]
                   (and (nil? academic-score) (nil? colloquial-score))))
       (into []))) ;; FIXME any way of optimizing the parameters of the scoring function?

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
    (let [cm (confusion-matrix (score-tokens (get-tokens test-data)) t p)]
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
        (let [cm (confusion-matrix (score-tokens (get-tokens test-data)) t p)]
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