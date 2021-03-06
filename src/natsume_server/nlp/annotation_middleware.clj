(ns natsume-server.nlp.annotation-middleware
  (:require [clojure.core.reducers :as r]
            [clojure.string :as string]
            [natsume-server.nlp.cabocha-wrapper :as cw]
            [natsume-server.nlp.unidic-conjugation :as unidic]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.set :as set])
  (:import [com.ibm.icu.text Transliterator Normalizer]
    #_[natsume_server.nlp.cabocha_wrapper Morpheme Chunk]))

;; With processing of user text we have to take care in respecting the
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
;; The original string should be kept and preferably its contents
;; written back into the CaboCha output morpheme's `orth` field.

(defn normalize-nfc
  [^String s]
  (Normalizer/normalize s Normalizer/NFC))

(defonce half-to-fullwidth (Transliterator/getInstance "Halfwidth-Fullwidth"))
(defn convert-half-to-fullwidth
  [^String s]
  (.transliterate ^Transliterator half-to-fullwidth s))

(defn set-union-safe [& sets]
  (into #{} (r/remove nil? (apply set/union (map #(if (set? %) % (set %)) sets)))))

(defn tree-to-morphemes-flat
  [t]
  (->> t (map :chunk/tokens) flatten))

(defn- repair-ids [tree removed-id]
  (mapv #(let [link (:chunk/link %)]
           (if (> link removed-id)
             (assoc % :chunk/link (dec link))
             %))
        tree))

(defn- add-begin-end-positions [chunk]
  (let [{:keys [chunk/head-begin-index chunk/head-end-index chunk/tail-begin-index chunk/tail-end-index chunk/tokens]} chunk
        head-begin (if head-begin-index (-> tokens (nth head-begin-index) :morpheme/begin))
        head-end (if head-end-index (-> tokens (nth (dec head-end-index)) :morpheme/end))
        tail-begin (if tail-begin-index (-> tokens (nth tail-begin-index) :morpheme/begin))
        tail-end (if tail-end-index (-> tokens (nth (dec tail-end-index)) :morpheme/end))]
    (merge (dissoc chunk :chunk/head :chunk/tail)
           {:chunk/head-begin head-begin :chunk/head-end head-end
            :chunk/tail-begin tail-begin :chunk/tail-end tail-end})))

;; ## Tagging middleware

;; ### Definitions of content and functional word classes
(def content-word-classes
  #{:verb :noun :adverb :adjective :preposition})

(def variable-word-classes
  #{:suffix :prefix :utterance :auxiliary-verb :symbol})    ; :symbol?

(def functional-word-classes
  #{:particle})                                             ; :symbol?

(def all-word-classes
  (set/union content-word-classes functional-word-classes variable-word-classes))

(defn- keywordize-ne
  [s]
  (if s
    (keyword (string/lower-case (string/replace s #"[BI]-" "")))))

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

(defn- recode-tags [m pos-code]
  (condp = pos-code
    :verb
    (condp = (:morpheme/lemma m)
      "出来る" #{:dekiru}
      "居る" #{:iru}
      "仕舞う" #{:simau}
      "ちゃう" #{:simau}
      "来る" #{:kuru}
      "貰う" #{:morau}
      "上げる" #{:ageru}
      ;; FIXME 始まる
      #{})
    :adjective
    (condp = (:morpheme/lemma m)
      "無い" #{:negative}
      "易い" #{:teido}                                        ; TODO
      "難い" #{:teido}                                        ; TODO
      "様" #{:you}
      "欲しい" #{:hoshii}
      "良い" #{:ii}
      "そう-伝聞" #{:sou-dengon}
      #{})
    :auxiliary-verb
    (cond
      (= (:morpheme/c-type m) "助動詞-タ") (if (re-seq #"仮定形" (:morpheme/c-form m))
                                         #{:potential}
                                         #{:past})
      (re-seq #"^助動詞-(ナイ|ヌ)$" (:morpheme/c-type m))
      (cond (re-seq #"く$" (:morpheme/orth m)) #{:aspect-ku :negative}
            (re-seq #"^仮定形" (:morpheme/c-form m)) #{:potential}
            :else #{:negative})
      (re-seq #"^ら?れる$" (:morpheme/lemma m)) #{:passive}
      (re-seq #"^さ?せる$" (:morpheme/lemma m)) #{:active}
      (re-seq #"^助動詞-(マス|デス)$" (:morpheme/c-type m)) #{:polite}
      (= (:morpheme/lemma m) "て") #{:aspect}
      (= (:morpheme/lemma m) "ば") #{:potential}
      (= (:morpheme/lemma m) "たり") #{:tari}
      :else #{})
    #{}))

(defn- add-tags [tokens]
  (->> tokens
       (mapv #(assoc % :morpheme/tags (set-union-safe (recode-tags % (:morpheme/pos %))
                                                      (if (not= "O" (:morpheme/ne %)) #{(keywordize-ne (:morpheme/ne %))} #{}))))))

;; TODO after tsutsuji integration, add semantic/etc. tags here (tag->for each token or natsume unigram?)

(s/fdef recode-token
  :args (s/cat :c :sentence/chunk)
  :ret :sentence/chunk)

(defn- recode-token
  "Recodes tokens in given chunk c into meta-information tags.
   Word class is recorded in :pos and other tags in :tags set."
  [c]
  (reduce
    (fn [r m]
      (let [pos-code (:morpheme/pos m)
            tags (recode-tags m pos-code)
            ne (if (not= "O" (:morpheme/ne m)) #{(keywordize-ne (:morpheme/ne m))} #{})]
        (conj r {:morpheme/pos pos-code :morpheme/tags (set-union-safe tags ne)})))
    []
    c))

;; ## Transition map
;;
;; Defines when POS should change or not.
;; TODO state machine: https://github.com/mtnygard/devs

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
      (merge (vector-map->map                               ; always transition with functional POS
               (for [c content-word-classes f functional-word-classes] [[c f] f])))
      (merge (vector-map->map                               ; ignore symbols (1)
               (for [pos all-word-classes]
                 [[pos :symbol] pos])))
      (merge (vector-map->map                               ; ignore symbols (2)
               (for [pos all-word-classes]
                 [[:symbol pos] pos])))
      (merge (vector-map->map                               ; every POS maps to itself
               (for [pos all-word-classes]
                 [[pos pos] pos])))
      ;; Manual transitions
      ;;       B               A           C
      (assoc [:verb :noun] :verb
             [:adjective :noun] :adjective                  ;; TODO here? make test case for 静的型検査
             [:adjective :verb] :adjective                  ;; 転がりやすい
             [:noun :adjective] :noun
             [:verb :adjective] :verb
             [:verb :prefix] :verb
             [:verb :adverb] :adverb                        ; TODO
             [:auxiliary-verb :verb] :verb
             [:auxiliary-verb :adjective] :adjective
             [:suffix :noun] :noun
             [:noun :prefix] :noun
             [:adverb :noun] :noun
             [:noun :particle] :noun
             [:noun :symbol] :noun)))

(comment
  (require '[automat.viz :refer [view save]])
  (require '[automat.core :as a])

  (defn make-transition-graph! []
    ;; TODO use transitions above
    (view
      (apply a/or (for [t transitions] (flatten t)))
      #_(a/or [:verb :noun :verb]
              [:adjective :noun :adjective]
              [:adjective :verb :adjective])))

  (defn make-pos-rename-graph! []
    (save (apply
            a/or
            [[#"^動詞" :verb]
             [#"^(副詞|名詞.+副詞可能)" :adverb]                    ;; TODO FIXME 副詞可能は名詞にする
             [#"^(代?名詞[^副]+|記号文字)" :noun]
             [#"^(形(容|状)詞|接尾辞形(容|状)詞的)" :adjective]
             [#"^助詞" (a/or
                       ["IF ((POS2==接続助詞 AND LEMMA==^(て|ば)$) OR LEMMA==^たり$)" :auxiliary-verb]
                       ["ELSE" :particle]
                       #_(if (or (and (= "接続助詞" (:pos-2 m))
                                      (re-seq #"^(て|ば)$" (:morpheme/lemma m)))
                                 (re-seq #"^たり$" (:morpheme/lemma m)))
                           :auxiliary-verb
                           :particle))]
             [#"^接続詞" :particle]
             [#"^((補助)?記号|空白)" :symbol]
             [#"^助動詞" (a/or ["IF C-TYPE==^助動詞-(ダ|デス)$" :particle]
                            ["ELSE" :auxiliary-verb]
                            #_(if (re-seq #"^助動詞-(ダ|デス)$" (:morpheme/c-type m))
                                :particle
                                :auxiliary-verb))]
             [#"^連体詞" :preposition]
             [#"^感動詞" :utterance]
             [#"^接頭辞" :prefix]
             [#"^接尾辞"
              (a/or ["IF POS2==動詞的" :adjective]
                    ["IF POS2==名詞的" :noun]
                    ["ELSE" :suffix]
                    #_(cond (= (:pos-2 m) "動詞的") :adjective ; ~がかった
                            (= (:pos-2 m) "名詞的") :noun      ; ~ら
                            :else :suffix))]])
          "pos-renames.png")))

(defn- reduce-with-transitions
  [reversed-tokens]
  (loop [tokens* reversed-tokens
         results []]
    (if-let [token (first tokens*)]
      (let [pos (:morpheme/pos token)
            tags (:morpheme/tags token)
            prev-token (peek results)
            ;; 1.
            new-pos (get transitions [(:morpheme/pos prev-token) pos])
            new-tags (set-union-safe tags (:morpheme/tags prev-token))]
        (recur (rest tokens*)
               (if (nil? new-pos)
                 (conj results token)                       ; Add token
                 (conj (pop results) {:morpheme/index (:morpheme/index token) ; Replace token
                                      :morpheme/pos   new-pos :morpheme/tags new-tags}))))
      results)))

(defn- remove-between-particles [xs]
  (let [ps (partition 3 1 xs)
        to-remove (->> ps
                       (map #(let [[a b c] %]
                               (if (= (:morpheme/pos a) (:morpheme/pos c) :particle)
                                 #{a b})))
                       (apply set-union-safe))]
    (if to-remove
      (remove to-remove xs)
      xs)))

;; Lemma-based normalization is experimental.
(def ^:dynamic *display-pos* #_:morpheme/lemma :morpheme/orth-base)

(defn- normalize-to-string
  "Given a chunk and begin and end token indexes, compounds the orthographic representations of
   tokens into bigger units in a smart way.

   For example, given 続け + られ + た, it will return 続けられる."
  [pos tags tokens]
  (letfn [(discard-symbols [token] (= :symbol (:morpheme/pos token)))
          ;; Replace uncommon orthography of lemmas with their more common counterpart (TODO data-driven + genre-driven)
          (normalize-lemma [s]
            (-> s
                (string/replace #"-.+$" "")
                (string/replace #"為る" "する")
                (string/replace #"有る" "ある")))

          (normalize-token [token]
            (let [pos (:morpheme/pos token)
                  goshu (:morpheme/goshu token)]
              (if (or (= goshu "記号")
                      (= goshu "外"))
                (:morpheme/orth-base token)                          ; Keep original for foreign scripts and AA
                (case pos
                  :particle (:morpheme/orth token)
                  :symbol (:morpheme/orth-base token)
                  (normalize-lemma (*display-pos* token))))))

          (normalize-token-with-conjugation [token]         ; specialization of above for verb-like tokens
            (if (and (#{:verb :adjective :auxiliary-verb} (:morpheme/pos token))
                     (re-seq #"(未然形|連用形|仮定形)" (:morpheme/c-form token)))
              (if (= *display-pos* :morpheme/lemma)
                (unidic/conjugate (update token :morpheme/lemma normalize-lemma))
                (:morpheme/orth token))
              ;; Defer to tail function
              (normalize-token token)))

          (pos-specific-cleanup
            [pos tokens]
            (let [cleaned
                  (case pos
                    :noun (->> tokens
                               reverse
                               (drop-while #(#{:auxiliary-verb :particle} (:morpheme/pos %)))
                               reverse)
                    :verb (take-while #(and (not (re-seq #"^(ます|てる?|ちゃう)$" (:morpheme/lemma %)))
                                            (not (re-seq #"助動詞-(タ|ダ|ナイ|ヌ|デス|ラシイ)" (:morpheme/c-type %))) ; FIXME should be whitelist
                                            (not (= :particle (:morpheme/pos %))))
                                      tokens)
                    :adjective (take-while #(not (or (#{:auxiliary-verb :particle} (:morpheme/pos %))
                                                     (seq (set/intersection #{:you :sou-dengon} (:morpheme/tags %))))) ; FIXME ように
                                           tokens)
                    tokens)]
              (if (empty? cleaned)
                (do #_(println "Failed to cleanup:" pos tokens "in" tokens)
                  tokens)
                cleaned)))]

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
  (let [maybe-head-tail (->> (map #(assoc %1 :morpheme/index %2) (recode-token c) (range (count c)))
                             reverse
                             vec
                             (apply-while-changed reduce-with-transitions) ; 2.
                             remove-between-particles)]
    (loop [m-h-ts maybe-head-tail                           ; 3.
           l (count c)
           m (zipmap [:chunk/head-pos :chunk/head-tags :chunk/head-begin-index :chunk/head-end-index :chunk/head-string :chunk/head-begin
                      :chunk/head-end :chunk/tail-pos :chunk/tail-tags :chunk/tail-begin-index :chunk/tail-end-index :chunk/tail-string
                      :chunk/tail-begin :chunk/tail-end] (repeat nil))]
      (if-let [{:keys [morpheme/index morpheme/pos morpheme/tags]} (first m-h-ts)]
        (let [tags (unify-aspect-tags tags)
              base-string (normalize-to-string pos tags (mapv #(get-in c [%]) (range index l)))]
          (recur (rest m-h-ts)
                 (int index)                                ; Type hint to make JVM happy.
                 (cond

                   (functional-word-classes pos)
                   (assoc m
                     :chunk/tail-pos pos :chunk/tail-tags tags :chunk/tail-begin-index index :chunk/tail-end-index l
                     :chunk/tail-string (str base-string (get m :chunk/head-string ""))
                     :chunk/tail-orth (str (->> (subvec c index l)
                                          (remove (fn [token] (= :symbol (:morpheme/pos token))))
                                          (map :morpheme/orth)
                                          (str/join ""))
                                     (get m :chunk/tail-orth "")))

                   (= :symbol pos) m

                   :else
                   (assoc m
                     :chunk/head-pos pos :chunk/head-tags tags :chunk/head-begin-index index :chunk/head-end-index l
                     :chunk/head-string (str base-string (get m :chunk/head-string "")) ; TODO >>前<<人口人間が言った
                     :chunk/head-orth (str (->> (subvec c index l)
                                          (remove (fn [token] (= :symbol (:morpheme/pos token))))
                                          (map :morpheme/orth)
                                          (str/join ""))
                                     (get m :chunk/head-orth ""))))))
        m))))

(defn- annotate-chunk
  [c]
  (merge c (infer-type-chunk (:chunk/tokens c))))

(defn- annotate-tree
  [t]
  (->> t
       (mapv annotate-chunk)
       (mapv add-begin-end-positions)))

;; ## Chunk Combining

;; 先生になる 演奏をする 太郎が先生になる
(defn- combine-chunks [a b update-function]
  (-> b
      (assoc :chunk/id (:chunk/id a))
      (update :chunk/link #(if-not (= -1 %) (dec %) %))
      update-function                                       ; TODO a NOOP right now
      (update :chunk/prob #(/ (+ % (:chunk/prob a)) 2))                 ;; CHECK
      (update :chunk/tokens #(apply conj (:chunk/tokens a) %))
      add-begin-end-positions))

;; TODO Pattern matching definitions as data: allow chunk type and
;; relation filtering, optional tokens, wildcards, variable windows,
;; AND/OR support...
;; Inspiration: http://emdros.org/mql.html
;;              http://corpora.dslo.unibo.it/TCORIS/QueryLanguage.html
;;              http://www.lrec-conf.org/proceedings/lrec2012/pdf/800_Paper.pdf
;; TODO efficient pattern match dispatch/compilation (kind of like
;; regular expressions). Look at: https://github.com/jclaggett/seqex
;; include one pattern in another by name
;; TODO Pattern rewriting/transformation in the definition. The
;; Tsutsuji dictionary patterns could be used as a test case.
;; TODO https://github.com/noprompt/frak generates regexes from seq of strings: can use this to make field matchers on-the-fly
(comment

  ;; more MQL-like
  [:chunk
   :ni-naru
   [[:token {:morpheme/orth "に"}]
    [:token {:morpheme/lemma #"[な成]る"}]]]

  ;; simpler data format (all levels (tokens, chunks) have the same
  ;; basic fields??)
  {:type   :chunk
   :filter {:type   :chunk/tokens
            :filter [{:morpheme/orth "に"} {:morpheme/lemma #"[な成]る"}]}
   :name   :ni-naru}

  ;; Tsutsuji example:
  ;; . 	. 	. 	. 	. 	. 	s 	01 	こと.に.なっ.て.い.ます 	J21 	A2 	敬体 	0 	2391M.1xx.46s01

  ;; FIXME These transformations/matches must be run in a certain order. For example: The Tsutsuji matches must take precedence over the more general noun+noun->noun type transformations. Should the longest match win? And should we iterate until no more transformations are found? Also, what about just simple 'transformations' that add tags?
  ;; FIXME Also, we might want to differentiate between transformations that should be merged back into the original/base sentence, or just used on their own to be converted to collocations, etc. This is important for the tokens search -- i.e. what unit do we want to capture -- and do we want tags and other contextual information too?
  {:name          :nouns
   :transform-ops [:compact]
   :match         {:type :sequence
                   :data [{:type :token :match {:morpheme/pos :noun} :repeat :+}]}}
  {:name          :noun->verb
   :transform-ops [:compact]}

  {:name          :chunk-normalization
   :transform-ops [:chunk-normalization]
   :match         {:type :chunk
                   :data [{:type :chunk}]}}
  (comment :dont-do-this-vvvvv
           {:name          :noun-particle-verb
            :transform-ops [:extract]
            :match         {:type :link-sequence
                            :data [{:type :chunk :match {:chunk/head-pos :noun :chunk/tail-pos :particle}}
                                   {:type :chunk :match {:chunk/head-pos :verb}}]}})
  ;; FIXME What is the right ordering c.f. POS tagging and other kinds of tagging.
  {:name          :verb-tags
   :tags          some-function
   :transform-ops [:add-tags]
   :match         {:type :single
                   :data [{:type :token :match {:morpheme/pos [:or #{:verb :auxiliary-verb}]}}]}}

  {:name          "こと.に.なっ.て.い.ます"
   :jlpt-level    1
   :tags          #{:polite}
   :transform-ops [:compact :add-tags]                      ;; Ordering matters. Add tags to what?
   :match         {:type :sequence                          ;; The below token vector would be automatically generated from Tsutsuji XML
                   :data [{:type :token :match {:morpheme/orth "こと" :morpheme/pos :noun}}
                          {:type :token :match {:morpheme/orth "に" :morpheme/pos :particle}}
                          {:type :token :match {:morpheme/lemma "成る" :morpheme/c-form "連用形-促音便" :morpheme/pos :verb}}
                          {:type :token :match [:morpheme/orth "て" :morpheme/pos :auxiliary-verb]}
                          {:type :token :match {:morpheme/orth "居る" :morpheme/pos :verb}}
                          {:type :token :match {:morpheme/orth "ます" :morpheme/pos :auxiliary-verb}}]}}
  )

(def patterns
  {[{:morpheme/orth "に"} {:morpheme/lemma #"^成る"}]                                                   :ni-naru
   [{:morpheme/orth "を"} {:morpheme/lemma #"^為る"}]                                                   :wo-suru ; サ変名詞＋を＋する <＝> サ変名詞する
   [{:morpheme/orth "に"} {:morpheme/lemma #"^(つく|因る|於く|対する)$" :morpheme/c-form #"^(連用形|仮定形)"}]       :fukugoujosi
   [{:morpheme/orth "に"} {:morpheme/lemma #"^於く$" :morpheme/c-form #"^命令形"} {:morpheme/orth #"^る$"}] :fukugoujosi})

;; TODO: Make pattern matching smarter by dispatching on matching
;;       fields in patterns and by saving intermediate results.
(defprotocol IMatchValue
  (match-value [pattern-val token-val]))
(extend-protocol IMatchValue

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

(s/fdef should-combine?
  :args (s/cat :a :sentence/chunk :b :sentence/chunk)
  :ret (s/nilable ifn?))

(defn should-combine?
  [a b]
  (let [a-tail-token (peek (:chunk/tokens a))               ;; FIXME TODO should check for dangling commas
        b-head-token (first (:chunk/tokens b))]
    (if (and (= :particle (:morpheme/pos a-tail-token))
             (= :verb (:morpheme/pos b-head-token)))
      (condp #(seq (set/intersection %1 %2))
             (pattern-match patterns (vector a-tail-token b-head-token))

        #{:ni-naru :wo-suru}
        ;; Merge a into b: add a head to b head and a tail to b head a先生+に|bなる -> b>先生<+>に<なる|
        (fn [b*] (-> b*
                     (update :chunk/head-string #(str (:chunk/head-string a) (:chunk/tail-string a) %))
                     (update :chunk/head-tags #(set-union-safe (:chunk/head-tags a) (:chunk/tail-tags a) %))
                     (update :chunk/tail-begin-index #(if % (+ % (count (:chunk/tokens a))) nil))
                     (update :chunk/tail-end-index #(if % (+ % (count (:chunk/tokens a))) nil))
                     (assoc :chunk/head-begin-index (:chunk/head-begin-index a))
                     (update :chunk/head-end-index #(+ % (count (:chunk/tokens a))))))
        #{:fukugoujosi}
        ;; Merge a into b: move b head to b tail and a head and tail to b head
        ;; FIXME Should only move the head of b to the tail of a if the tail of b is empty.
        (fn [b*] (-> b*
                     (assoc :chunk/head-begin-index (:chunk/head-begin-index a))
                     (assoc :chunk/head-end-index (:chunk/head-end-index a))
                     (assoc :chunk/head-tags (:chunk/head-tags a))
                     (assoc :chunk/head-string (:chunk/head-string a))
                     (assoc :chunk/head-pos (:chunk/head-pos a))
                     (assoc :chunk/tail-string (str (:chunk/tail-string a) (:chunk/head-string b*) (:chunk/tail-string b*)))
                     (assoc :chunk/tail-tags (set-union-safe (:chunk/head-tags b*) (:chunk/tail-tags a)))
                     (assoc :chunk/tail-pos :fukugoujosi)
                     (update :chunk/tail-begin-index #(if % (+ % (count (:chunk/tokens a))) nil))
                     (update :chunk/tail-end-index #(if % (+ % (count (:chunk/tokens a))) nil))))
        nil))))

(defn- add-tags-to-tokens [chunks]
  (mapv (fn [chunk]
          (update chunk :chunk/tokens add-tags))
        chunks))

(defn- modify-tree [tree]
  (loop [coll []
         chunks tree
         delete-count 0]
    (if (seq chunks)
      (let [chunk (-> (first chunks)
                      (update :chunk/id #(- % delete-count))
                      (update :chunk/link #(if-not (= -1 %) (- % delete-count) %)))
            previous-chunk (peek coll)
            combined (if previous-chunk (should-combine? previous-chunk chunk))]
        (recur (if combined
                 (repair-ids (conj (pop coll) (combine-chunks previous-chunk chunk combined)) (:chunk/id chunk))
                 (conj coll chunk))
               (next chunks)
               (if combined (inc delete-count) delete-count)))
      coll)))

(defn- revert-orth-with
  "Reverts the :orth of all tokens in tree to their pre-NFC norm form."
  [tree original-input]
  (letfn [(update-morpheme [m] (assoc m :morpheme/orth (subs original-input (:morpheme/begin m) (:morpheme/end m))))
          (update-morphemes [ms] (mapv update-morpheme ms))
          (update-chunk [c] (update c :chunk/tokens update-morphemes))]
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

;; TODO use transducers

(s/fdef sentence->tree
  :args (s/cat :s string?)
  :ret (s/coll-of :sentence/chunk))

(defn sentence->tree
  "Converts string into CaboCha tree data structure."
  [s]
  (-> s
      normalize-nfc                                         ; 2.
      convert-half-to-fullwidth                             ; 2.
      (string/replace "．" "。")                              ; 3.
      (string/replace "，" "、")                              ; 3.
      cw/parse-sentence-synchronized                        ; 4.
      add-tags-to-tokens                                    ; 5.
      annotate-tree                                         ; 5.
      modify-tree                                           ; 5.
      (revert-orth-with s)))                                ; 6.

(defn sentence->cabocha [s]
  (-> s
      normalize-nfc                                         ; 2.
      convert-half-to-fullwidth                             ; 2.
      (string/replace "．" "。")                              ; 3.
      (string/replace "，" "、")                              ; 3.
      cw/parse-sentence-synchronized                        ; 4.
      ))

(defn cabocha->tree [cabocha-output s]
  (-> cabocha-output
      add-tags-to-tokens                                    ; 5.
      annotate-tree                                         ; 5.
      modify-tree                                           ; 5.
      (revert-orth-with s)))

(comment
  (sentence->tree "フェイスブック（ＦＢ）やツイッターなどソーシャルメディアを使った採用活動が、多くの企業に広がっている。")
  (sentence->tree "適当な措置を採るよう求める。"))
