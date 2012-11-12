(ns natsume-server.cabocha-wrapper
  (:require [clojure.string :as string]
            [clojure.java.shell :as shell]
            [clojure.zip :as z]
            [taoensso.timbre :as log]
            [natsume-server.log-config :as lc])
  (:use
   [lamina core]
   [aleph http formats]
   [clojure.set :only (union)])
  (:import [com.ibm.icu.text Transliterator Normalizer]))

(lc/setup-log log/config :error)

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

;; Is NFKC the right norm here?
(defn normalize-nfc
  [^String s]
  (Normalizer/normalize s Normalizer/NFC))

;; This is apparently very heavyweight, so we should make sure we are
;; only loadinig it once (I am not sure this is the right way).
(defonce half-to-fullwidth (Transliterator/getInstance "Halfwidth-Fullwidth"))
(defn convert-half-to-fullwidth
  [^String s]
  (.transliterate half-to-fullwidth s))

;; TODO look into using eda as a supplement to CaboCha
(defn run-cabocha-on-string
  "This part is necessitated because I failed to get the Java bindings to link (TODO).
   The use of numerous config options is because we want to make sure that we are using UniDic for MeCab and then also loading the right CaboCha models."
  [s]
  (:out (shell/sh "cabocha"
                  "-d" "/usr/lib64/mecab/dic/unidic"
                  "-b" "/usr/lib64/mecab/dic/unidic/dicrc"
                  "-r" "/etc/cabocharc-unidic"
                  "-P" "UNIDIC"
                  "-f1"
                  :in s)))

;; modified from aleph/test/websocket.clj
(defmacro with-server [server & body]
  `(let [kill-fn# ~server]
     (try
       ~@body
       (finally
         (kill-fn#)))))

(defmacro with-handler [handler & body]
  `(with-server (start-http-server ~handler {:port 8081 :websocket true})
     ~@body))

(defn websocket-handler [ch req]
  (siphon ch req)) ; used to be ch ch

(defonce cabocha-websocket-channel
  #(deref (websocket-client {:url "ws://localhost:8080/ws/json"})))

(defn get-cabocha-websocket
  "For a given string returns a CaboCha analyzed string using WebSockets."
  [s]
  (with-handler websocket-handler
    (let [ch (cabocha-websocket-channel)]
      (enqueue ch s)
      (let [r (decode-json @(read-channel ch))]
        (close ch) ; FIXME understand websockets
        r))))

(defn parse-cabocha-header
  [s]
  (let [[id link head-tail prob] (rest (string/split s #"\s"))
        [head tail] (string/split head-tail #"/")]
    (assoc {}
      :id   (Integer/parseInt id)
      :link (Integer/parseInt (string/replace link "D" ""))
      :head (Integer/parseInt head)
      :tail (Integer/parseInt tail)
      :prob (Double/parseDouble prob)
      :tokens []))) ; tokens are not needed here IMHO

(def unidic-features
  "A vector of all UniDic features, in order, as defined in the UniDic Manual (p. ?) version `1.3.12`."
  [:pos1 :pos2 :pos3 :pos4 :cType :cForm :lForm :lemma :orth :pron
   :kana :goshu :orthBase :pronBase :kanaBase :formBase :iType :iForm
   :iConType :fType :fForm :fConType :aType :aConType :aModType])

(defn parse-cabocha-morpheme
  "Parses MeCab/UniDic (via CaboCha) formatted output line and converts it into a map.
   TODO possibly make this into a record, if performance becomes an issue."
  [s position]
  (let [[orth features ne] (string/split s #"\t")
        split-features (string/split features #",")]
    ;;(when (< (count split-features) 9) (pprint split-features))
    ;;(println (get split-features 11))
    (if (< (count split-features) 9)
      (assoc (zipmap unidic-features split-features)
        :lemma    orth    ; Added
        :orthBase orth ; Added
        :goshu    "不明"
        :ne       ne
        :begin    position
        ;;:end    (+ position (.length (nth split-features 8)))
        :end      (+ position (.length orth)))
      (assoc (zipmap unidic-features split-features)
        :ne    ne
        :begin position
        ;;:end (+ position (.length (nth split-features 8)))
        :end   (+ position (.length orth)))))) ;; :orth is at index 8

(defn is-header? [s] (re-find #"^\*[^\t\*]+$" s))
(defn is-eos? [s] (= "EOS" s))
(defn parse-cabocha-format
  "Parses CaboCha output and returns a nested list of chunks (with
  associated id, link, etc.) and tokens (morphemes)."
  [s]
  (:out (let [lines (string/split-lines s)] ; discard position index and only return :out
          (reduce
           (fn [accum line]
             (cond
               (is-header? line) (update-in accum
                                            [:out]
                                            #(conj % (parse-cabocha-header line)))
               (is-eos? line) accum ; do nothing
               :else (let [last-chunk-index (dec (count (get-in accum [:out])))
                           morpheme (parse-cabocha-morpheme line (:position accum))]
                       ;; append to last chunk
                       (-> accum
                           (update-in [:out last-chunk-index :tokens] #(conj % morpheme))
                           (assoc :position (inc (:end morpheme))))))) ; add new position
           {:position 0 :out []}
           ;; TODO find better way to have 2 separate reduce accumulators
           lines))))

(defn tree-to-morphemes-flat
  [t]
  (->> t (map :tokens) flatten))

;; ## Tagging middleware
;;
;; FIXME what about `名詞＋を＋する／名詞＋に＋なる`?

;; ### Definitions of content and functional word classes
(def content-word-classes
  #{:verb :noun :adverb :adjective :preposition})

(def variable-word-classes
  #{:suffix :prefix :utterance :auxiliary-verb}) ; :symbol?

(def functional-word-classes
  #{:p :symbol})

(defn- recode-pos
  "Recodes morphemes in given chunk c into simple POS keywords."
  [c]
  (reduce
   (fn [r m]
     (let [pos (str (:pos1 m) (:pos2 m) (:pos3 m))
           cType (:cType m)]
       (conj r
             (cond
              (re-seq #"^(副詞|名詞.+副詞可能)" pos) :adverb
              (re-seq #"^代?名詞[^副]+" pos) :noun
              (re-seq #"^連体詞" pos) :preposition
              (re-seq #"^動詞" pos) :verb
              (re-seq #"^助詞" pos) :p
              (re-seq #"^(形容詞|接尾辞形容詞的)" pos) :adjective
              (re-seq #"^(補助)?記号" pos) :symbol
              (and (re-seq #"^助動詞" pos)
                   (re-seq #"^助動詞-(ダ|デス)$" cType)) :p
              (re-seq #"^助動詞" pos) :auxiliary-verb
              (re-seq #"^形状詞" pos) :adjective
              (re-seq #"^感動詞" pos) :utterance
              (re-seq #"^接頭辞" pos) :prefix
              (re-seq #"^接尾辞" pos) :suffix
              :else :unknown-pos))))
   []
   c))

;; ## Transition map
;;
;; Defines when POS should change or not.

(defn- vector-map->map
  "Helper function to make a map from map-like vectors."
  [v]
  (apply merge (map #(apply hash-map %) v)))

(def transitions
  (merge {[:verb :noun] :verb
          [:adjective :noun] :adjective
          [:verb :adjective] :verb
          [:auxiliary-verb :verb] :verb
          [:auxiliary-verb :adjective] :adjective
          [:noun :symbol] :noun}
         (vector-map->map
          (for [c content-word-classes f functional-word-classes] [[c f] f]))
         (vector-map->map
          (for [pos (union content-word-classes functional-word-classes variable-word-classes)]
            [[pos pos] pos]))))

(defn- enumerate
  "Enumerates (indexes) given sequence."
  [s]
  (map-indexed vector s))

(defn- update-if-not-nil
  [m k v]
  (let [curr-v (get m k)]
    (if (nil? (curr-v))
      v
      curr-v)))

(defn- reduce-with-transitions
  [indexed-tokens]
  (log/debug (format "START REDUCE: '%s'" indexed-tokens))
  (reduce
   (fn [accum i-t]
     (log/debug (format "accum: '%s'\ti-t: '%s'" accum i-t))
     (let [new-t (get transitions
                      [(second (peek accum))
                       (second i-t)])] ; 1.
       (log/debug
        (format "new-t: '%s'; input: '%s'" new-t [(second (peek accum)) (second i-t)]))
       (if (nil? new-t)
         ;; Replace token:
         (do (log/debug (format "not replacing %s" i-t)) (conj accum i-t))
         (do (log/debug (format "replacing with %s" new-t)) (conj (pop accum) [(first i-t) new-t])))))
   []
   indexed-tokens))

(defn- make-composite-token-string
  [begin end c]
  (log/debug (format "begin: '%s' end: '%s' c: '%s'" begin end c))
  (apply str (map #(get-in (vec c) [% :orth]) (range begin end))))

(defn- infer-type-chunk
  "Infers the content and functional parts of the chunk in reverse order.
   Also corrects the :head and :tail indexes given by CaboCha, which are sometimes wrong.

   1. Apply transition on reversed token sequence.
      When two tokens match, they are recombined into the transitioned one.
      The lesser token index is kept.
   2. Repeat 1. until no matches are found.
   3. Assign :tail and :tail-type if functional token exists, likewise for content token.

   Possible chunk types are: content only, functional only, and content and functional.

   TODO: In reality, the functional part can (and should) be split into two possible parts.
         Example: 言ったかも知れないが = 言った=noun + かも知れない=functional_1 + が=functional_2
         In this example, functional_1 would be modality, while funcitonal_1 would be normal case particle"
  [c]
  (let [indexed-tokens (reverse (enumerate (recode-pos c)))
        maybe-head-tail (loop [trans-i-tokens indexed-tokens] ; 2.
                          (log/debug (format "LOOP: '%s'" trans-i-tokens))
                          (let [reduced (reduce-with-transitions trans-i-tokens)]
                            (if (not= trans-i-tokens reduced)
                              (recur reduced)
                              trans-i-tokens)))]
    (log/debug maybe-head-tail)
    (second
     (reduce ; 3.
      (fn [[l m] [i t]] ; l = last index, m = map
        (if (functional-word-classes t)
          [i (assoc m :tail-type t :tail i :tail-string (make-composite-token-string i (if (nil? l) (count c) l) c))]
          [i (assoc m :head-type t :head i :head-string (make-composite-token-string i (if (nil? l) (count c) l) c))]))
      [nil
       {:head-type nil :tail-type nil :tail-string nil
        :head      nil :tail      nil :head-string nil}]
      maybe-head-tail))))

(defn- annotate-chunk
  [c]
  (merge c (infer-type-chunk (:tokens c))))

(defn- annotate-tree
  [t]
  (map annotate-chunk t))

(defn- revert-orth-with
  "Reverts the :orth of all tokens in tree to their pre-NFC norm form."
  [tree original-input]
  (log/trace (format "tree: '%s'\noriginal-input: '%s'" (apply str (map :orth (tree-to-morphemes-flat tree))) original-input))
  (let [update-morpheme #(assoc % :orth (subs original-input (:begin %) (:end %)))
        update-morphemes #(map update-morpheme %)
        update-chunk #(update-in % [:tokens] update-morphemes)]
    (map update-chunk tree)))

;; Pre-processing (normalization, common substitutions, etc.) is done on the string before it is processed by CaboCha.
;; The whole process is as follows:
;;
;; 1. save original string for later step
;; 2. do Unicode NFC on the input string
;; 3. substitute all occurrences of `．，` with `。、`, and half- with full-width characters
;; 4. send resulting string to CaboCha
;; 5. tag all chunks by chunk type (noun phrase, adjectival phrase etc. by scanning head-tail information (:head-type = :noun, :tail-type :p-ga, etc.)
;; 5. join certain classes of chunks like `プレゼントをする`, i.e. `名詞＋を＋する／名詞＋に＋なる`
;; 6. replace the `orth` fields of all morphemes in CaboCha output with characters in the original string
(defn string-to-tree
  "Converts string into CaboCha tree data structure."
  [s]
  (-> s
      normalize-nfc             ;2
      convert-half-to-fullwidth  ;2
      (string/replace "．" "。") ;3
      (string/replace "，" "、") ;3
      ;;run-cabocha-on-string    ;4
      ;;parse-cabocha-format     ;4
      get-cabocha-websocket      ;4 ;; faster until we get JNI bindings working
      (revert-orth-with s)
      annotate-tree
      ))

;; # TODO
;;
;; - is there any way to parse CaboCha input with zippers?
;;
;;   Doing it with zippers should clean up the code and make it possible to have real 'nested' type structures.
;;   After more consideration, there is no way to make a tree structure represent the dual-linked (adjacency and dependency link) graph structure of CaboCha dependency grammar trees, short of making it recurive in nature/making custom tree traversal code.
;;   Therefore making separate custom traversal code for both adjacency and link structure makes more sense (at the expense of having two ways of traversing trees instead of one -- which is unavoidable anyway?).
;;   Zippers can still be used to construct the initial structure.
;;   We should use vectors to support `nth` type access with O(1) costs.
