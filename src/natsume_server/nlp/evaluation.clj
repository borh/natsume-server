(ns natsume-server.nlp.evaluation
  (:require [schema.core :as s]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clojure.core.match :refer [match]]
            [clojure.core.reducers :as r]

            [natsume-server.component.database :as db]
            [natsume-server.nlp.error :as error]))

(s/defschema Token
             {:orth-base s/Str :lemma s/Str :pos-1 s/Str
              :academic-written (s/maybe s/Bool) :normal-written (s/maybe s/Bool)
              :public-spoken (s/maybe s/Bool) :normal-spoken (s/maybe s/Bool)})

(s/defschema ScoredToken
             (assoc Token :academic-score (s/maybe s/Bool) :colloquial-score (s/maybe s/Bool)))

(s/defn get-tokens :- [Token]
  [adverb-file :- s/Str]
  (->> (with-open [adverb-reader (io/reader adverb-file)]
         (doall (csv/read-csv adverb-reader :separator \tab :quote 0)))
       (r/drop 1)
       (r/map (fn [[表層形 左文脈ID 右文脈ID コスト 品詞大分類 品詞中分類 品詞小分類 品詞細分類 活用型 活用形 語彙素読み 語彙素 書字形出現形 発音形出現形 書字形基本形 発音形基本形 語種 語頭変化型 語頭変化形 語末変化型 語末変化形 アカデミックな書き言葉 一般的な書き言葉 公的な話し言葉 日常の話し言葉 備考]]
                ;; We only need a few features to match.
                {:orth-base 書字形出現形
                 :lemma 語彙素
                 :pos-1 品詞大分類
                 :academic-written (case アカデミックな書き言葉   "○" true "×" false nil)
                 :normal-written   (case 一般的な書き言葉      "○" true "×" false nil)
                 :public-spoken    (case 公的な話し言葉       "○" true "×" false nil)
                 :normal-spoken    (case 日常の話し言葉       "○" true "×" false nil)}))
       (into [])))

(def conn (db/druid-pool {:subname "//localhost:5432/natsumedev"
                          :user "natsumedev"
                          :password "riDJMq98LpyWgB7F"}))

(s/defn score-tokens :- [ScoredToken]
  [;;conn :- s/Any
   tokens :- [Token]]
  (for [{:keys [orth] :as m} tokens]                      ;; There will be duplicates for lemmas, so going with orth (probably not good idea though, should just directly query database and not use MeCab/CaboCha at all....)
    (let [score (->> m
                     (error/token-register-score conn)
                     ;;(error/get-error conn)
                     ;;:results
                     ;;first
                     :register-score
                     :verdict)]
      ;;(println (error/token-register-score conn m))
      (assoc m :academic-score score :colloquial-score (case score true false false true nil nil)))))           ;; FIXME any way of optimizing the parameters of the scoring function?

(comment
  (score-tokens (get-tokens "data/unidic-adverb1219.tsv"))
  (filter #(and (:score %) (:normal-spoken %)) (score-tokens (get-tokens "data/unidic-adverb1219.tsv"))))

(s/defn save-table
  [fn :- s/Str
   tokens :- [ScoredToken]]
  (let [ks (keys ScoredToken)]
    (with-open [w (io/writer fn)]
      (csv/write-csv w (into [(mapv name ks)] (mapv #(mapv % ks) tokens)) :separator \tab :quote 1))))

(comment (save-table "data/unidic-adverb1219-scored.tsv"
                     (score-tokens (get-tokens "data/unidic-adverb1219.tsv"))))

;; TODO precision/recall

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
                       [true   true] :tp [false  true] :fp
                       [true  false] :fn [false false] :tn
                       :else :NA)
                inc)))
    {:tp 0 :fp 0 :fn 0 :tn 0 :NA 0}
    tokens))

(s/defn precision :- s/Num
  [cm :- ConfusionMatrix]
  (let [{:keys [tp fp]} cm]
    (double (/ tp (+ tp fp)))))

(s/defn recall :- s/Num
  [cm :- ConfusionMatrix]
  (let [{:keys [tp fn]} cm]
    (double (/ tp (+ tp fn)))))

(s/defn f1 :- s/Num
  [cm :- ConfusionMatrix]
  (/ (* 2.0 (precision cm) (recall cm))
     (+ (precision cm) (recall cm))))

(comment
  (f1 (confusion-matrix (score-tokens (get-tokens "data/unidic-adverb1219.tsv")) :normal-spoken :colloquial-score))
  (f1 (confusion-matrix (score-tokens (get-tokens "data/unidic-adverb1219.tsv")) :academic-written :academic-score)))

(s/set-fn-validation! true)