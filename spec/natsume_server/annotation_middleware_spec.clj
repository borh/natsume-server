(ns natsume-server.annotation-middleware-spec
  (:require [speclj.core :refer :all]
            [natsume-server.annotation-middleware :refer :all]))

;; ## Test data

;; ### Word class test data
;;
;; Word class test data is represented in two forms:
;;
;; - regular vector of strings
;; - map of strings to tags
;;
;; The two formats are (somewhat) interchangeble using the following two helper functions.

(defn add-pos-tag
  "Adds specified tag to input vector of test strings."
  [prefix pos m]
  (let [pos-key  (keyword (str prefix "-pos"))
        tag-key  (keyword (str prefix "-tags"))]
    (cond
     (map? m)    (into {} (map (fn [[k v]] {k {pos-key pos tag-key v  }}) m))
     (vector? m) (into {} (map (fn [x]     {x {pos-key pos tag-key #{}}}) m))
     :else (println "Input must be map or vector."))))

(defn remove-tags
  "Removes tags from map of tests."
  [m] (apply vector (map first m)))

(def test-nouns
  {"名詞"           #{}
   "複合名詞"       #{}
   "複合名詞句"     #{}
   "国立国語研究所" #{:organization}
   "田中さん"       #{:person}
   "東京都"         #{:location}
   "これ"           #{}
   "私"             #{}})

(def tagged-nouns (add-pos-tag "head" :noun test-nouns))

;; TODO ている -> 助詞,接続助詞
;; TODO になる…、ができる？
;; TODO add modality test data from 2009 JNLP paper
;; TODO "よかろうだろうが" test
;; TODO :kuru, :morau, etc.
(def tagged-verbs
  (add-pos-tag "head" :verb
   {"言う"                 #{}
    "言った"               #{:past}
    "言わない"             #{:negative}
    "言わなかった"         #{:negative :past}
    "言います"             #{:polite}
    "言いません"           #{:polite :negative}
    "言いました"           #{:polite :past}
    "言いませんでした"     #{:polite :past :negative}
    "言わせる"             #{:active}
    "言わせた"             #{:active :past}
    "言わせられる"         #{:active :passive}
    "言っている"           #{:aspect-iru}
    "言ってしまう"         #{:aspect-simau}
    "言ってくる"           #{:aspect-kuru}
    "降り始める"           #{}
    "降り始めた"           #{:past}
    "複合する"             #{}
    "複合させる"           #{:active}
    "複合できる"           #{:dekiru}    ; FIXME :dekiru
    "複合できなかった"     #{:dekiru :past :negative} ; FIXME :dekiru
    "複合させられる"       #{:active :passive}
    "複合させられた"       #{:past :active :passive}
    "複合させられなかった" #{:past :negative :active :passive}
    "遊覧飛行する"         #{}
    "遵守しなければならない" #{} ; FIXME
    "申せば"               #{:potential} ; FIXME
    "話したら"             #{:potential} ; FIXME
    "巻いたりしている"     #{:tari :aspect-iru} ; FIXME
    }))

(def test-verbs (remove-tags tagged-verbs))

(def test-adverbs
  ["絶対"       #{}
   "昨日"       #{}
   "そういった" #{:past}])

(def tagged-adverbs (add-pos-tag "head" :adverb test-adverbs))

(def test-adjectives
  {"綺麗"                   #{}
   "綺麗な"                 #{}
   "シャープな"             #{}
   "愛しい"                 #{}
   ;; POS is OK, but take care with collocation type -> :adjectivalAdverbVerb
   "愛しく"                 #{} ; TODO -> :adverb?; collocation type /is/ adverbial, but POS is :adjective
   "愛しくなかった"         #{:negative :past}
   "愛しくありませんでした" #{:negative :past :polite} ; 2 chunks, but this is OK
   "愛しかった"             #{:past}
   "転がりやすい"           #{}
   "我慢強い"               #{}
   "黒がかった"             #{}})

(def tagged-adjectives (add-pos-tag "head" :adjective test-adjectives))

(def test-prepositions
  ["この"
   "いわゆる"
   "大きな"
   "大した"])

(def tagged-prepositions (add-pos-tag "head" :preposition test-prepositions))

(def test-conjunctive-particles
  ["が"
   "のに"
   "ので"
   "から"])

(def tagged-conjunctive-particles (add-pos-tag "tail" :particle test-conjunctive-particles))

(def test-particles
  ["が"
   "を"
   "に"
   "で"
   "と"
   "から"
   "より"
   "も"
   "において"
   "においての"
   "に関して"
   "におきまして"])

(def tagged-particles (add-pos-tag "tail" :particle test-particles))

(def test-symbols
  ["!"
   "！"
   "。"
   "."])

(def tagged-symbols (add-pos-tag "tail" :symbol test-symbols))

;; Tests

(defn permute-strings
  [xs ys]
  (for [x xs y ys] (str x y)))

(defn permute-tags
  [xs ys]
  (into {} (for [[x-string x-tags] xs [y-string y-tags] ys]
             {(str x-string y-string) (merge x-tags y-tags)})))

(defn permute-chunk-tags
  [xs ys]
  (into {} (for [[x-string x-tags] xs [y-string y-tags] ys]
             {(str x-string y-string) [x-tags y-tags]})))

(defn get-test-tags
  [x]
  (let [[expected-string expected-tags] x
        expected-keys (keys expected-tags)
        test-tree (sentence->tree expected-string)
        test-tags (first (map #(select-keys % expected-keys) test-tree))]
    test-tags))

(defn get-sentence-test-tags
  [x]
  (let [[expected-string expected-tags] x
        expected-keys (if (vector? expected-tags)
                        (mapv keys expected-tags)
                        [(keys expected-tags)])
        test-tree (sentence->tree expected-string)
        test-tags (mapv #(select-keys %1 %2) test-tree expected-keys)]
    test-tags))

(defmacro check-tags-helper
  [tests]
  `(for [t# ~tests]
     (it (str (first t#) " has matching pos and meta tags")
         (should= (second t#) (get-test-tags t#)))))

(defmacro check-sentence-tags-helper
  [tests]
  `(let [t# ~tests]
     (it (str (first t#) " has matching pos and meta tags")
         (should= (second t#) (get-sentence-test-tags t#)))))

(describe
 "Word class:"

 ;; Checking single word class tagging (either head or tail only)
 (for [word-class [tagged-nouns
                   tagged-verbs
                   tagged-adverbs
                   tagged-adjectives
                   tagged-prepositions
                   tagged-particles
                   tagged-symbols]]
   (check-tags-helper word-class))

 ;; Checking compound word class tagging (head and tail)
 (let [noun+particle      (permute-tags tagged-nouns tagged-particles)
       verb+particle      (permute-tags tagged-verbs tagged-conjunctive-particles)
       adjective+particle (permute-tags tagged-adjectives tagged-conjunctive-particles)]
   (for [compound-classes [noun+particle
                           verb+particle
                           adjective+particle]]
     (check-tags-helper compound-classes)))
 )

(describe
 "NPV:" ; WARNING: running these tests will take some time.

 (let [noun+particle (permute-tags tagged-nouns tagged-particles)
       npvs          (permute-chunk-tags noun+particle tagged-verbs)]
   (for [npv (take 2 npvs)]
     (check-sentence-tags-helper npv)))

 ;; FIXME what about `名詞＋を＋する／名詞＋に＋なる`?
 )

(describe
 "Unicode normalization and character conversion"

 (it "normalizes with NFC, preserving unicode character counts"
     (should= "…" (normalize-nfc "…"))) ; would become "。。。" with NFKC

 (it "converts halfwidth to fullwidth characters"
     (should= "。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン゙゚" (convert-half-to-fullwidth "｡｢｣､･ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝﾞﾟ")))
 )
