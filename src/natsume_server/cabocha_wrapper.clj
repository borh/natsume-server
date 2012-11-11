(ns natsume-server.cabocha-wrapper
  (:require [clojure.string :as string]
            [clojure.java.shell :as shell]
            [clojure.zip :as z])
  (:use [taoensso.timbre :as timbre :only (trace debug info warn fatal spy)]
        ;; timbre error collision with one of aleph or lamina
        [lamina core]
        [aleph http formats])
  (:import [com.ibm.icu.text Transliterator Normalizer]))

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
(defn normalize-nfkc
  [^String s]
  (Normalizer/normalize s Normalizer/NFKC))

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

(defn tree-branch?
  [t]
  (or (vector? t)
      (contains? t :tokens)))

(defn tree-children
  [t]
  (if (contains? t :tokens) (:tokens t) t))

(defn make-tree-node
  [t children]
  (into (if (map? (first t)) [(first t)] [t])
        children))

(defn cabocha-zipper
  "Custom zipper for the CaboCha tree data structure."
  [root]
  (z/zipper
   tree-branch?
   tree-children
   make-tree-node
   root))

(defn sequential-traverse-apply-tree
  "TODO this is nice, but too limiting...."
  [t f]
  (loop [loc (z/down (cabocha-zipper t))]
    (cond
      (z/end? loc) (z/root loc)
      (contains? (z/node loc) :tokens) (recur (z/down loc))
      :else (recur (z/next
                    (do (f (z/node loc))
                        loc))))))

;; The basic structure should look like this:
;;
;; [chunk,chunk,chunk,...]
;;
;; where chunk = [token,token,...]
;;
;; and token is a map of features.
;;
;; Restrictions on links between chunks are that one chunk can have only one outward link,
;; but multiple incoming links are allowed.
;;
;; Operations on trees we want to support are:
;; 1. Process adjacent chunks as a sequence
;; 2. Process chunks by dependency link order, which includes
;;    A. going from the head to the tail, and then to the tails tail, and so on ... in depth-first fashion
;;    B. going from the tail to *all* the heads, in a breadth-first order
;;
;; So, extracting NPV relations would entail:
;; 1. Search the adjecent chunk sequence for Ns in linear order
;; 2. For each N, check if N is paired with the right P and its tail is a V
;;
;; Rewriting "名詞を＋する" and "名詞に＋なる" type adjacent chunk combinations into one bigger chunk,
;; and setting that chunk's :head-type to "verb", and :tail-type to last chunk's :tail-type:
;; 1. Search the adjacent chunk sequence for "する" or "なる" :head-type
;; 2. Edit tree to include new combination chunk and remove old affected chunks
;;
;; We will have rules that depend on sequences of conditions that must match over sequences of morphemes/chunks/links.
;; Therefore a stack of need-to-match conditions is needed.
;; Again, can this be expressed with FSMs?

;; p: current position in the form of {:chunk-id X :morpheme-id Y}
(defn next-chunk
  "Returns the next adjacent chunk in `t`.
   Returns nil if last chunk."
  [t]
  (cond
    (z/end? t) (do ;(pprint "next-chunk:end")
                 (z/root t)) ; (z/root t) ; z/root for last chunk??? how do we know if we are done?
    (contains? (z/node t) :tokens) (do ;(pprint "next-chunk:chunk")
                                       (-> t z/right)) ; in chunk
    (nil? (-> t z/up z/right)) (do ;(pprint "next-chunk:last-chunk")
                                 [(z/node t) :end])

    :else (do ;(pprint "next-chunk:morpheme")
            (-> t z/up z/right))))                ; in morpheme

(defn prev-chunk
  "TODO Try to cleanup next-chunk, then rework for here."
  [t]
  (cond
    (z/end? t) (do ;(pprint "next-chunk:end")
                 (z/root t)) ; (z/root t) ; z/root for last chunk??? how do we know if we are done?
    (contains? (z/node t) :tokens) (do ;(pprint "next-chunk:chunk")
                                     (-> t z/left)) ; in chunk
    (nil? (-> t z/up z/left)) (do ;(pprint "next-chunk:last-chunk")
                                   [(z/node t) :end])
    :else (do ;(pprint "next-chunk:morpheme")
            (-> t z/up z/right)))
  )

(defn next-morpheme
  [t]
  (cond
   (z/end? t) (do #_(pprint "next-morpheme:end") t) ; (z/root t)
    (contains? (z/node t) :tokens) (do
                                     ;;(pprint "next-morpheme:chunk")
                                     (-> t z/down)) ; in chunk
    (nil? (z/right t)) (-> t next-chunk next-morpheme)
    #_(if (nil? (-> t z/up z/right))
                         (do ;(pprint "next-morpheme:last-chunk+last-morpheme")
                             [( z/node t) :end])
                         (do ;(pprint "next-morpheme:last-morpheme")
                             (-> t next-chunk next-morpheme))) ; last morpheme in chunk
    :else (do ;(pprint "next-morpheme:morpheme")
              (-> t z/right))))      ; in morpheme

(defn prev-morpheme
  "TODO"
  [t])

(defn next-linked-chunk
  "TODO
  If in token, go up, check :link and go to it (use :link - :id for distance).
  If in chunk ...
  If :link is -1, end."
  [t]
  (cond
    (z/end? t) t
    (contains? (z/node t) :tokens) (if (= :link -1)
                                     t
                                     (repeatedly (- :link :id) (next-chunk t)))
    :else (next-linked-chunk (z/up t))))

(defn tree-to-morphemes-function
  [t f]
  (loop [loc (-> (cabocha-zipper t) z/down z/down)]
    (if (z/end? loc)
      (z/root loc)
      (do ;(pprint :recur)
          (recur (next-morpheme
                  (do (f (z/node loc))
                      loc)))))))

(defn tree-to-morphemes-flat
  [t]
  (->> t (map :tokens) flatten))


;; Tagging middleware
;;
;; Using core.logic to further chunk morphemes into bigger units
;; called Natsume Units (NU), which are made from SUWs and can be
;; longer than LUWs, but never cross chunk (bunsetsu) boundaries.
;; FIXME what about `名詞＋を＋する／名詞＋に＋なる`?

(defn infer-type
  [])

(defn chunk-dispatch
  [c]
  (let [type (infer-type c)]
    (case type
      :noun ()))) ; FIXME

;; Pre-processing (normalization, common substitutions, etc.) is done on the string before it is processed by CaboCha.
;; The whole process is as follows:
;;
;; 1. save original string for later step
;; 2. do Unicode NFKC on the input string
;; 3. substitute all occurrences of `．，` with `。、`, and half- with full-width characters
;; 4. send resulting string to CaboCha
;; 5. tag all chunks by chunk type (noun phrase, adjectival phrase etc. by scanning head-tail information (:head-type = :noun, :tail-type :p-ga, etc.)
;; 5. join certain classes of chunks like `プレゼントをする`, i.e. `名詞＋を＋する／名詞＋に＋なる`
;; 6. replace the `orth` fields of all morphemes in CaboCha output with characters in the original string
(defn string-to-tree
  "Converts string into CaboCha tree data structure."
  [s]
  (-> s
      normalize-nfkc             ;2
      convert-half-to-fullwidth  ;2
      (string/replace "．" "。") ;3
      (string/replace "，" "、") ;3
      ;;run-cabocha-on-string    ;4
      get-cabocha-websocket      ;4 ;; faster until we get JNI bindings working
      parse-cabocha-format       ;4
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