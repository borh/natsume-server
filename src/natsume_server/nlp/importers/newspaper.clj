(ns natsume-server.nlp.importers.newspaper
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [natsume-server.nlp.text :as text])
  (:import [com.ibm.icu.text Transliterator]))

;; Mainichi Newspaper format corpus reader.
;; http://www.nichigai.co.jp/sales/pdf/man_mai.pdf
;; http://www.nichigai.co.jp/sales/pdf/man_yomi_j.pdf

(defonce full-to-halfwidth (Transliterator/getInstance "Fullwidth-Halfwidth"))
(defn convert-full-to-halfwidth
  [^String s]
  (.transliterate ^Transliterator full-to-halfwidth s))

(def mai-category-map
  {"０１" "1面"
   "０２" "2面"
   "０３" "3面"
   "０４" "解説"
   "０５" "社説"
   "０７" "国際"
   "０８" "経済"
   "１０" "特集"
   "１２" "総合"
   "１３" "家庭"
   "１４" "文化"
   "１５" "読書"
   "１６" "科学"
   "１７" "生活"
   "１８" "芸能"
   "３５" "スポーツ"
   "４１" "社会"})

(def y6-category-map
  {"Ｑ０１" "皇室"
   "Ｒ０１" "国際"
   "Ｒ０２" "アジア 太平洋"
   "Ｒ０３" "南北アメリカ"
   "Ｒ０４" "西欧"
   "Ｒ０５" "旧ソ連・東欧"
   "Ｒ０６" "中東"
   "Ｒ０７" "アフリカ"
   "Ｓ０１" "科学"
   "Ｓ０２" "宇宙"
   "Ｓ０３" "地球"
   "Ｓ０４" "理工学"
   "Ｓ０５" "生命工学"
   "Ｓ０６" "動植物"
   "Ｔ０１" "犯罪・事件"
   "Ｔ０２" "事故"
   "Ｔ０３" "災害"
   "Ｕ０１" "生活"
   "Ｕ０２" "健康"
   "Ｕ０３" "衣"
   "Ｕ０４" "食"
   "Ｕ０５" "住"
   "Ｕ０６" "余暇"
   "Ｕ０７" "行事"
   "Ｖ０１" "文化"
   "Ｖ０２" "学術"
   "Ｖ０３" "美術"
   "Ｖ０４" "映像"
   "Ｖ０５" "文学"
   "Ｖ０６" "音楽"
   "Ｖ０７" "演劇"
   "Ｖ０８" "芸能"
   "Ｖ０９" "舞踊"
   "Ｖ１０" "宗教"
   "Ｗ０１" "スポーツ"
   "Ｗ０２" "巨人軍"
   "Ｘ０１" "社会"
   "Ｘ０２" "市民運動"
   "Ｘ０３" "社会保障"
   "Ｘ０４" "環境"
   "Ｘ０５" "婦人"
   "Ｘ０６" "子供"
   "Ｘ０７" "中高年"
   "Ｘ０８" "勲章・賞"
   "Ｘ０９" "労働"
   "Ｘ１０" "教育"
   "Ｙ０１" "経済"
   "Ｙ０２" "財政"
   "Ｙ０３" "金融"
   "Ｙ０４" "企業"
   "Ｙ０５" "中小企業"
   "Ｙ０６" "技術"
   "Ｙ０７" "情報"
   "Ｙ０８" "サービス"
   "Ｙ０９" "貿易"
   "Ｙ１０" "国土・都市計画"
   "Ｙ１１" "鉱工業"
   "Ｙ１２" "資源・エネルギー"
   "Ｙ１３" "農林水産"
   "Ｚ０１" "政治"
   "Ｚ０２" "右翼・左翼"
   "Ｚ０３" "選挙"
   "Ｚ０４" "行政"
   "Ｚ０５" "地方自治"
   "Ｚ０６" "司法"
   "Ｚ０７" "警察"
   "Ｚ０８" "日本外交"
   "Ｚ０９" "軍事"
   "Ｚ１０" "戦争"})

(defn process-doc [tagged-lines]
  (reduce
   (fn [m [tag s]]
     (if (= s "【現在著作権交渉中の為、本文は表示できません】")
       m
       (case tag
         "ＡＤ"
         (assoc-in m [:sources :genre]
                   (if-let [category (get mai-category-map s)]
                     ["新聞" "毎日新聞" category]
                     (if-let [category (get y6-category-map s)]
                       ["新聞" "読売新聞" category]
                       (do (throw (Exception. (format "Unknown newspaper category '%s'" s)))
                           ["新聞"]))))

         "Ｃ０"
         (-> m
             (assoc-in [:sources :basename] s)
             (assoc-in [:sources :year]
                       (let [year-fragment
                             (convert-full-to-halfwidth (if (== (count s) 9)
                                                          (subs s 0 2)
                                                          (subs s 2 4)))]
                         (Integer/parseInt
                          (if (= \9 (first year-fragment))
                            (str "19" year-fragment)
                            (str "20" year-fragment))))))

         "Ｔ１"
         (assoc-in m [:sources :title] s)

         "Ｔ２"
         (update m :paragraphs
                 (fn [paragraphs]
                   (conj paragraphs
                         {:tags #{}
                          :sentences (text/split-japanese-sentence s)})))

         m)))
   {:paragraphs []
    :sources {:author "" :permission false}}
   tagged-lines))

(defn doc-start? [s]
  (if (= (subs s 0 4) "＼ＩＤ＼")
    true))

(defn split-tags [s]
  (->> s
       (re-seq #"^＼([^＼]+)＼(.+)$")
       first
       rest
       vec))

(defn doc-seq
  [filename]
  (let [lines (line-seq (io/reader filename))]
    (sequence (comp (partition-by doc-start?)
                    (partition-all 2)
                    (map flatten)
                    (map #(map split-tags %))
                    (map process-doc))
              lines)))
