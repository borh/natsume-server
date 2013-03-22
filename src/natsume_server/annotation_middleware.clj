(ns natsume-server.annotation-middleware
  (:require [clojure.string :as string]
            [clojure.set :as set]
            [clojure.core.reducers :as r]
            [clojure.pprint :refer [pprint]]
            ;;[clojure.core.match :refer [match]]
            ;;[clojure.core.logic :as l]
            ;;[pldb.logic :as pldb]
            [plumbing.core :refer [?>]]
            [natsume-server.unidic-conjugation :as unidic]
            [natsume-server.cabocha-wrapper :as cw]
            [taoensso.timbre :as log]
            [natsume-server.log-config :as lc])
  (:import [com.ibm.icu.text Transliterator Normalizer]))

(lc/setup-log log/config :trace)

;; With processing of user text we have to take care in repecting the
;; users preferences, such as the type of periods and commas he/she
;; prefers, or preferences between full/half-width characters. In
;; essence, all user input should be returned the way it was input in.
;; This problem comes up because CaboCha prefers a certain kind of
;; input, but also because we want to remove any superficial
;; differences in script use and keep as much data that is
;; semantically equivalent together.
;;
;; Before sending the input to CaboCha, it should be normalized,
;; stripped of invalid codepoints, and all half-width characters
;; should be converted to full-width, etc.
;; Most importantly all instances of the delimiters `．，` should be
;; replace with `。、`.
;; The original string should be kept and preferrably its contents
;; written back into the CaboCha output morpheme's `orth` field.

(defn normalize-nfc
  [^String s]
  (Normalizer/normalize s Normalizer/NFC))

(defonce half-to-fullwidth (Transliterator/getInstance "Halfwidth-Fullwidth"))
(defn convert-half-to-fullwidth
  [^String s]
  (.transliterate ^Transliterator half-to-fullwidth s))

(defn tree-to-morphemes-flat
  [t]
  (->> t (map :tokens) flatten))

(defn- repair-ids [tree removed-id]
  (mapv #(let [link (:link %)]
           (if (>= link removed-id)
             (assoc % :link (dec link))
             %))
        tree))

(defn- add-begin-end-positions [chunk]
  (let [{:keys [head-begin-index head-end-index tail-begin-index tail-end-index tokens]} chunk
        head-begin (if head-begin-index (-> tokens (nth head-begin-index) :begin))
        head-end   (if head-end-index   (-> tokens (nth (dec head-end-index))   :end))
        tail-begin (if tail-begin-index (-> tokens (nth tail-begin-index) :begin))
        tail-end   (if tail-end-index   (-> tokens (nth (dec tail-end-index))   :end))]
    (merge (dissoc chunk :head :tail)
           {:head-begin head-begin :head-end head-end
            :tail-begin tail-begin :tail-end tail-end})))

;; ## Tagging middleware
;;
;; FIXME what about `名詞＋を＋する／名詞＋に＋なる`?

;; ### Definitions of content and functional word classes
(def content-word-classes
  #{:verb :noun :adverb :adjective :preposition})

(def variable-word-classes
  #{:suffix :prefix :utterance :auxiliary-verb :symbol}) ; :symbol?

(def functional-word-classes
  #{:particle}) ; :symbol?

(def all-word-classes
  (set/union content-word-classes functional-word-classes variable-word-classes))

(defn- keywordize-ne
  [s]
  (keyword (string/lower-case (string/replace s #"[BI]-" ""))))

(defn- unify-aspect-tags
  "Unifies the separate :aspect and #{:iru :simau :kuru ...} tags into one :aspect-* tag."
  [a]
  (let [match (set/select #{:iru :simau :kuru :hoshii :ii} a)]
    (if (not-empty match)
      (if (:aspect a)
        (conj (apply disj a :aspect match)
              (keyword (str "aspect-" (string/join "+" (map name match)))))
        (apply disj a :aspect match))
      a)))

(defn- recode-pos [m]
  (condp #(re-seq %1 %2) (str (:pos1 m) (:pos2 m) (:pos3 m))
    #"^代?名詞[^副]+" :noun
    #"^動詞" :verb
    #"^(形(容|状)詞|接尾辞形(容|状)詞的)" :adjective
    #"^(副詞|名詞.+副詞可能)" :adverb
    #"^助詞接続助詞" (if (= "て" (:lemma m))
                       :auxiliary-verb
                       :particle) ; TODO
    #"^(助|接続)詞" :particle
    #"^((補助)?記号|空白)" :symbol
    #"^助動詞" (if (re-seq #"^助動詞-(ダ|デス)$" (:cType m))
                 :particle
                 :auxiliary-verb)
    #"^連体詞" :preposition
    #"^感動詞" :utterance
    #"^接頭辞" :prefix
    #"^接尾辞" :suffix
    :unknown-pos))

(defn- add-pos [tokens]
  (->> tokens
       (mapv #(assoc % :pos (recode-pos %)))))

(defn- recode-tags [m pos-code]
  (condp = pos-code
    :verb
    (condp = (:lemma m)
      "出来る" #{:dekiru}
      "居る"   #{:iru}
      "仕舞う" #{:simau}
      "ちゃう" #{:simau}
      "来る"   #{:kuru}
      #{})      ; TODO 貰う、あげる…
    :adjective
    (condp = (:lemma m)
      "無い"      #{:negative}
      "易い"      #{:teido} ; TODO
      "難い"      #{:teido} ; TODO
      "様"        #{:you}
      "欲しい"    #{:hoshii}
      "良い"      #{:ii}
      "そう-伝聞" #{:sou-dengon}
      #{})
    :auxiliary-verb
    (cond
     (= (:cType m) "助動詞-タ") (if (re-seq #"仮定形" (:cForm m))
                                  #{:potential}
                                  #{:past})
     (re-seq #"^助動詞-(ナイ|ヌ)$" (:cType m)) (if (re-seq #"く$" (:orth m))
                                                 #{:aspect-ku :negative}
                                                 #{:negative})
     (re-seq #"^ら?れる$" (:lemma m))            #{:passive}
     (re-seq #"^さ?せる$" (:lemma m))            #{:active}
     (re-seq #"^助動詞-(マス|デス)$" (:cType m)) #{:polite}
     (= (:lemma m) "て")                         #{:aspect}
     :else                                       #{})
    #{}))

(defn- add-tags [tokens]
  (->> tokens
       (mapv #(assoc % :tags (set/union (recode-tags % (:pos %))
                                        (if (not= "O" (:ne %)) #{(keywordize-ne (:ne %))} #{}))))))

(defn- recode-token
  "Recodes tokens in given chunk c into meta-information tags.
   Word class is recorded in :pos and other tags in :tags set."
  [c]
  (reduce
   (fn [r m]
     (let [pos-code (recode-pos m)
           tags     (recode-tags m pos-code)
           ne       (if (not= "O" (:ne m)) #{(keywordize-ne (:ne m))} #{})]
       (conj r {:pos pos-code :tags (set/union tags ne)})))
   []
   c))

;; ## Transition map
;;
;; Defines when POS should change or not.

(defn- vector-map->map
  "Helper function to make a map from map-like vectors."
  [v]
  (apply merge (map #(apply hash-map %) v)))

;; FIXME 酒はないようだ where よう is added to the verb --- why not
;; just add it as a tag

;; Sentence order: A B
;; B . A -> C (transition map written in reverse order)
(def transitions
  (-> {}
      (merge (vector-map->map ; always transition with functional POS
              (for [c content-word-classes f functional-word-classes] [[c f] f])))
      (merge (vector-map->map ; ignore symbols (1)
              (for [pos all-word-classes]
                [[pos :symbol] pos])))
      (merge (vector-map->map ; ignore symbols (2)
              (for [pos all-word-classes]
                [[:symbol pos] pos])))
      (merge (vector-map->map ; every POS maps to itself
              (for [pos all-word-classes]
                [[pos pos] pos])))
      ;; Manual transitions
      ;;       B               A           C
      (assoc [:verb           :noun]      :verb
             [:adjective      :noun]      :adjective
             [:adjective      :verb]      :verb
             [:noun           :adjective] :noun
             [:verb           :adjective] :verb
             [:verb           :prefix]    :verb
             [:verb           :adverb]    :adverb ; TODO
             [:auxiliary-verb :verb]      :verb
             [:auxiliary-verb :adjective] :adjective
             [:suffix         :noun]      :noun
             [:noun           :prefix]    :noun
             [:adverb         :noun]      :noun
             [:noun           :particle]  :noun
             [:noun           :symbol]    :noun)))

(defn- reduce-with-transitions
  [reversed-tokens]
  (loop [tokens* reversed-tokens
         results []]
    (if-let [token (first tokens*)]
      (let [pos (:pos token)
            tags (:tags token)
            prev-token (peek results)
            ;; 1.
            new-pos (get transitions [(:pos prev-token) pos])
            new-tags (set/union tags (:tags prev-token))]
        (recur (rest tokens*)
               (if (nil? new-pos)
                 (conj results token) ; Add token
                 (conj (pop results) {:index (:index token) ; Replace token
                                      :pos new-pos :tags new-tags}))))
      results)))

(defn- remove-between-particles [xs]
  (let [ps (partition 3 1 xs)
        to-remove (->> ps
                       (map #(let [[a b c] %]
                               (if (= (:pos a) (:pos c) :particle)
                                 #{a b})))
                       (apply set/union))]
    (if to-remove
      (remove to-remove xs)
      xs)))

;; Lemma-based normalization is experimental.
(def ^:dynamic *display-pos* #_:lemma :orthBase)

(defn- normalize-to-string
  "Given a chunk and begin and end token indexes, compounds the orthographic representations of
   tokens into bigger units in a smart way.

   For example, given 続け + られ + た, it will return 続けられる."
  [pos tags tokens]
  (letfn [(discard-symbols [token] (re-seq #"^((補助)?記号|空白)$" (:pos1 token)))

          (normalize-lemma [s]
            (-> s
                (string/replace #"-.+$" "")
                (string/replace #"為る" "する")
                (string/replace #"有る" "ある")))

          (normalize-token [token]
            (let [pos (:pos token)
                  goshu (:goshu token)]
              (if (or (= goshu "記号")
                      (= goshu "外"))
                (:orthBase token) ; Keep original for foreign scripts and AA
                (case pos
                  :particle (:orth token)
                  :symbol   (:orthBase token)
                  (normalize-lemma (*display-pos* token))))))

          (normalize-token-with-conjugation [token] ; specialization of above for verb-like tokens
            (if (and (#{:verb :adjective :auxiliary-verb} (:pos token))
                     (re-seq #"(未然形|連用形|仮定形)" (:cForm token)))
              (if (= *display-pos* :lemma)
                (unidic/conjugate (update-in token [:lemma] normalize-lemma))
                (:orth token))
              ;; Defer to tail function
              (normalize-token token)))

          (pos-specific-cleanup [pos tokens]
            (case pos
              :noun (->> tokens
                         reverse
                         (drop-while #(#{:auxiliary-verb :particle} (:pos %)))
                         reverse)
              :verb (take-while #(and (not (re-seq #"^(ます|てる?|ちゃう)$" (:lemma %)))
                                      (not (re-seq #"助動詞-(タ|ダ|ナイ|ヌ|デス|ラシイ)" (:cType %))) ; FIXME should be whitelist
                                      (not (= :particle (:pos %))))
                                tokens)
              :adjective (take-while #(not (or (#{:auxiliary-verb :particle} (:pos %))
                                               (seq (set/intersection #{:you :sou-dengon} (:tags %))))) ; FIXME ように
                                     tokens)
              tokens))]

    (if-let [ts (->> tokens
                     (remove discard-symbols)
                     (pos-specific-cleanup pos)
                     seq)]
      (let [ts-vec (vec ts)
            head (pop ts-vec)
            tail (peek ts-vec)]
        (str (->> head
                  (mapv normalize-token-with-conjugation)
                  string/join)
             (normalize-token tail))))))

(defn- apply-while-changed [f coll]
  (let [coll-new (f coll)]
    (if (not= coll-new coll)
      (recur f coll-new)
      coll-new)))

(defn- infer-type-chunk
  "Infers the content and functional parts of the chunk in reverse order.
   Also corrects the :head and :tail indexes given by CaboCha, which are sometimes wrong.

   1. Apply transition on reversed token sequence.
      When two tokens match, they are recombined into the transitioned one (based on transitions map).
      The lesser token index is kept.
   2. Repeat 1. until no matches are found.
   3. Assign :tail and :tail-pos if functional token exists, likewise for content token.

   All combinations of the three chunk types are possible: content, function, and modality.

   TODO: In reality, the functional part can (and should) be split into two possible parts.
         Example: 言ったかも知れないが = 言った=noun + かも知れない=functional_1 + が=functional_2
         In this example, functional_1 would be modality, while functional_2 would be normal case particle"
  [c]
  (let [maybe-head-tail (->> (map #(assoc %1 :index %2) (recode-token c) (range (count c)))
                             reverse
                             vec
                             (apply-while-changed reduce-with-transitions) ; 2.
                             remove-between-particles)]
    (loop [m-h-ts maybe-head-tail ; 3.
           l (count c)
           m (zipmap [:head-pos :head-tags :head-begin-index :head-end-index :head-string :head-begin
                      :head-end :tail-pos :tail-tags :tail-begin-index :tail-end-index :tail-string
                      :tail-begin :tail-end] (repeat nil))]
      (if-let [{:keys [index pos tags]} (first m-h-ts)]
        (let [tags (unify-aspect-tags tags)
              base-string (normalize-to-string pos tags (mapv #(get-in c [%]) (range index l)))]
          (recur (rest m-h-ts)
                 index
                 (cond

                  (functional-word-classes pos)
                  (assoc m
                    :tail-pos pos :tail-tags tags :tail-begin-index index :tail-end-index l
                    :tail-string (str base-string (get m :head-string "")))

                  (= :symbol pos) m

                  :else
                  (assoc m
                    :head-pos pos :head-tags tags :head-begin-index index :head-end-index l
                    :head-string (str base-string (get m :head-string "")))))) ; TODO >>前<<人口人間が言った
        m))))

(defn- annotate-chunk
  [c]
  (merge c (infer-type-chunk (:tokens c))))

(defn- annotate-tree
  [t]
  (->> t
       (mapv annotate-chunk)
       (mapv add-begin-end-positions)))

;; ## Chunk Combining

;; 先生になる 演奏をする 太郎が先生になる
(defn- combine-chunks [a b update-function]
  (-> b
      (assoc :id (:id a))
      (update-in [:link] #(if-not (= -1 %) (dec %) %))
      update-function ; TODO a NOOP right now
      (update-in [:prob] #(/ (+ % (:prob a)) 2)) ;; CHECK
      (update-in [:tokens] #(apply conj (:tokens a) %))))

(def patterns
  {[{:orth "に"} {:lemma #"^成る"}] :ni-naru
   [{:orth "を"} {:lemma #"^為る"}] :wo-suru ; サ変名詞＋を＋する <＝> サ変名詞する
   [{:orth "に"} {:lemma #"^(つく|因る|於く|対する)$" :cForm #"^(連用形|仮定形)"}] :fukugoujosi
   [{:orth "に"} {:lemma #"^於く$" :cForm #"^命令形"} {:orth #"^る$"}] :fukugoujosi})

;; TODO: Make pattern matching smarter by dispatching on matching
;;       fields in patterns and by saving intermediate results.
(defprotocol match-dispatch
  (match-value [pattern-val token-val]))
(extend-protocol match-dispatch

  java.util.regex.Pattern
  (match-value [pattern-val token-val]
    ((complement nil?) (re-seq pattern-val token-val)))

  java.lang.String
  (match-value [pattern-val token-val]
    (= pattern-val token-val)))

(defn partial-match [pattern token]
  (every? true?
          (for [[k v] pattern]
            (match-value v (k token)))))

(defn pattern-match [patterns tokens]
  (->> (for [[pattern pattern-type] patterns
             :when (<= (count pattern) (count tokens))]
         (if (every? true? (map partial-match pattern tokens))
           pattern-type))
       (remove nil?)
       (into #{})))

(defn should-combine? [a b]
  (let [a-tail-token (peek (:tokens a)) ;; FIXME TODO should check for dangling commas
        b-head-token (first (:tokens b))]
    (if (and (= :particle (:pos a-tail-token))
             (= :verb     (:pos b-head-token)))
      (condp #(seq (set/intersection %1 %2)) (pattern-match patterns (vector a-tail-token b-head-token))

        #{:ni-naru :wo-suru}
        ;; Merge a into b: add a head to b head and a tail to b head a先生+に|bなる -> b>先生<+>に<なる|
        (fn [b*] (-> b*
                    (update-in [:head-string] #(str (:head-string a) (:tail-string a) %))
                    (update-in [:head-tags]   #(set/union (:head-tags a) (:tail-tags a) %))
                    (update-in [:tail-begin-index] #(if % (+ % (count (:tokens a))) nil))
                    (update-in [:tail-end-index]   #(if % (+ % (count (:tokens a))) nil))
                    (assoc      :head-begin-index (:head-begin-index a))
                    (update-in [:head-end-index] #(+ % (count (:tokens a))))))
        #{:fukugoujosi}
        ;; Merge a into b: move b head to b tail and a head and tail to b head
        ;; FIXME Should only move the head of b to the tail of a if the tail of b is empty.
        (fn [b*] (-> b*
                    (assoc :head-begin-index (:head-begin-index a))
                    (assoc :head-end-index   (:head-end-index   a))
                    (assoc :head-tags        (:head-tags   a))
                    (assoc :head-string      (:head-string a))
                    (assoc :tail-string (str (:tail-string a) (:head-string b*) (:tail-string b*)))
                    (assoc :tail-tags (set/union (:head-tags b*) (:tail-tags a)))
                    (update-in [:tail-begin-index] #(if % (+ % (count (:tokens a))) nil))
                    (update-in [:tail-end-index]   #(if % (+ % (count (:tokens a))) nil))))
        nil))))

(defn- add-pos-tags-to-tokens [chunks]
  (mapv (fn [chunk]
          (-> chunk
              (update-in [:tokens] add-pos)
              (update-in [:tokens] add-tags)))
        chunks))

(defn- modify-tree [tree]
  (loop [coll []
         chunks tree
         delete-count 0]
    (if chunks
      (let [chunk (-> (first chunks)
                      (update-in [:id] #(- % delete-count))
                      (update-in [:link] #(if-not (= -1 %) (- % delete-count) %)))
            previous-chunk (peek coll)
            combined (if previous-chunk (should-combine? previous-chunk chunk))]
        (recur (if combined
                 (repair-ids (conj (pop coll) (combine-chunks previous-chunk chunk combined)) (:id chunk))
                 (conj coll chunk))
               (next chunks)
               (if combined (inc delete-count) delete-count)))
      coll)))

(defn- revert-orth-with
  "Reverts the :orth of all tokens in tree to their pre-NFC norm form."
  [tree original-input]
  (let [update-morpheme  #(assoc % :orth (subs original-input (:begin %) (:end %)))
        update-morphemes #(mapv update-morpheme %)
        update-chunk     #(update-in % [:tokens] update-morphemes)]
    (mapv update-chunk tree)))

;; Pre-processing (normalization, common substitutions, etc.) is done on the string before it is processed by CaboCha.
;; The whole process is as follows:
;;
;; 1. save original string for later step
;; 2. do Unicode NFC on the input string
;; 3. substitute all occurrences of `．，` with `。、`, and half- with full-width characters
;; 4. send resulting string to CaboCha
;; 5. tag all chunks by chunk type (noun phrase, adjectival phrase etc. by scanning head-tail information (:head-pos = :noun, :tail-pos :p-ga, etc.)
;; 5. join certain classes of chunks like `プレゼントをする`, i.e. `名詞＋を＋する／名詞＋に＋なる`
;; 6. replace the `orth` fields of all morphemes in CaboCha output with characters in the original string
(defn sentence->tree
  "Converts string into CaboCha tree data structure."
  [s]
  (-> s
      normalize-nfc              ; 2.
      convert-half-to-fullwidth  ; 2.
      (string/replace "．" "。") ; 3.
      (string/replace "，" "、") ; 3.
      cw/parse-sentence          ; 4.
      add-pos-tags-to-tokens     ; 5.
      annotate-tree              ; 5.
      modify-tree                ; 5.
      (revert-orth-with s)))     ; 6.