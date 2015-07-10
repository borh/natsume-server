(ns natsume-server.nlp.cabocha-wrapper
  (:require [clojure.string :as string]
            [qbits.knit :as knit]
            [schema.core :as s])
  (:import [org.chasen.cabocha Parser Token]
           [clojure.lang PersistentHashSet IDeref]))

;; # Simple CaboCha JNI Wrapper

(defn thread-local*
  [init]
  (let [generator (proxy [ThreadLocal] []
                    (initialValue [] (init)))]
    (reify IDeref
      (deref [this]
        (.get generator)))))

(defonce parser (thread-local* (fn [] (Parser.))))

(def ^:private unidic-features
  "A vector of all UniDic features, in order, as defined in the UniDic Manual (p. ?) version `2.1.1`."
  [:pos-1 :pos-2 :pos-3 :pos-4 :c-type :c-form :l-form :lemma :orth :pron :orth-base :pron-base :goshu :i-type :i-form :f-type :f-form])

(s/defrecord Morpheme
  [pos :- (s/maybe s/Keyword)
   pos-1 :- s/Str
   pos-2 :- s/Str
   pos-3 :- s/Str
   pos-4 :- s/Str
   c-type :- s/Str
   c-form :- s/Str
   l-form :- (s/maybe s/Str)
   lemma :- s/Str
   orth :- s/Str
   pron :- (s/maybe s/Str)
   orth-base :- s/Str
   pron-base :- s/Str
   goshu :- s/Str
   i-type :- (s/maybe s/Str)
   i-form :- (s/maybe s/Str)
   f-type :- (s/maybe s/Str)
   f-form :- (s/maybe s/Str)
   ne :- (s/maybe s/Str)
   begin :- s/Num
   end :- s/Num
   tags :- (s/maybe PersistentHashSet)])

;; TODO Check if maybe's are justified. This also comes back to the utility of the Chunk as a unified record type, as it undergoes a lot of transformations along the way (hence the maybe's). Two defschema (Chunk + AnnotatedChunk) might be the best-performing and safe solution.
(s/defrecord Chunk
  [id :- s/Num
   link :- s/Num
   head :- (s/maybe s/Num)
   tail :- (s/maybe s/Num)
   head-string :- (s/maybe s/Str)
   head-begin :- (s/maybe s/Num)
   head-end :- (s/maybe s/Num)
   head-pos :- (s/maybe s/Keyword)
   head-tags :- (s/maybe PersistentHashSet)
   head-begin-index :- (s/maybe s/Num)
   head-end-index :- (s/maybe s/Num)
   tail-string :- (s/maybe s/Str)
   tail-begin :- (s/maybe s/Num)
   tail-end :- (s/maybe s/Num)
   tail-pos :- (s/maybe s/Keyword)
   tail-tags :- (s/maybe PersistentHashSet)
   tail-begin-index :- (s/maybe s/Num)
   tail-end-index :- (s/maybe s/Num)
   prob :- s/Num
   tokens :- [Morpheme]])

(s/defn recode-pos :- s/Keyword
  [m]
  (condp re-seq (str (:pos-1 m) (:pos-2 m) (:pos-3 m))
    #"^動詞" :verb
    #"^(副詞|名詞.+副詞可能)" :adverb
    #"^(代?名詞[^副]+|記号文字)" :noun
    #"^(形(容|状)詞|接尾辞形(容|状)詞的)" :adjective
    #"^助詞" (if (or (and (= "接続助詞" (:pos-2 m))
                          (re-seq #"^(て|ば)$" (:lemma m)))
                     (re-seq #"^たり$" (:lemma m)))
               :auxiliary-verb
               :particle)
    #"^接続詞" :particle
    #"^((補助)?記号|空白)" :symbol
    #"^助動詞" (if (re-seq #"^助動詞-(ダ|デス)$" (:c-type m))
                 :particle
                 :auxiliary-verb)
    #"^連体詞" :preposition
    #"^感動詞" :utterance
    #"^接頭辞" :prefix
    #"^接尾辞" (cond (= (:pos-2 m) "動詞的") :adjective ; ~がかった
                     (= (:pos-2 m) "名詞的") :noun ; ~ら
                     :else :suffix)
    :unknown-pos))

(s/defn parse-token :- Morpheme
  [^Token token position surface token-length]
  (let [features  (string/split (.getFeature token) #",")
        ne        (.getNe token)
        token-map (-> unidic-features
                      (zipmap features)
                      (assoc :orth  surface
                             :ne    ne
                             :begin position
                             :end   (+ position token-length)))
        token-map (assoc token-map :pos (recode-pos token-map))]
    (map->Morpheme
     (if (= (count features) 6)
       (assoc token-map
         :lemma     surface
         :orth-base surface
         :pron-base surface ; FIXME translate to katakana.
         :goshu     "不明")
       token-map))))

(s/defn parse-tokens :- [Morpheme]
  [tokens :- [Token]
   position :- s/Num]
  (loop [tokens*   tokens
         position* position
         parsed    []]
    (if-let [^Token token (first tokens*)]
      (let [token-surface (.getSurface token)
            token-length  (count token-surface)]
       (recur (rest tokens*)
              (+ position* token-length)
              (conj parsed (parse-token token position* token-surface token-length))))
      parsed)))

(s/defn parse-sentence ;;:- [Chunk] ; Too many changes in Chunk to benefit from record here.
  "Parses input sentence string and returns a vector of CaboCha chunks containing dependency
   information and a vector of tokens, which are represented as maps."
  [s :- String] ;; s/Str gives reflection warnings
  (let [tree   (.parse ^Parser @parser s)
        chunks (.chunk_size tree)]
    (loop [chunk-id 0
           token-id 0
           position 0
           parsed   []]
      (if (< chunk-id chunks)
        (let [chunk   (.chunk tree chunk-id)
              link-id (.getLink chunk)
              prob    (.getScore chunk)
              head    (.getHead_pos chunk)
              tail    (.getFunc_pos chunk)
              token-count (.getToken_size chunk)
              token-list  (mapv #(.token tree %) (range token-id (+ token-id token-count)))
              tokens      (parse-tokens token-list position)]
          (recur
           (inc chunk-id)
           (+ token-id token-count)
           (int (:end (last tokens)))
           (conj parsed
                 {:id chunk-id
                  :link link-id
                  :head head
                  :tail tail
                  :prob prob
                  :tokens tokens})))
        parsed))))

;; FIXME consider core.async ala https://github.com/lynaghk/zmq-async/blob/master/src/com/keminglabs/zmq_async/core.clj
;; TODO Or rather, reimplement bindings with JNA

(defn callable-parse-sentence [^String s]
  (cast Callable (fn [] (parse-sentence s))))
(defn cabocha-thread-factory
  "Returns a new ThreadFactory instance wrapped with a new CaboCha instance."
  [thread-group]
  (reify java.util.concurrent.ThreadFactory
    (newThread [_ f]
      (doto (Thread. ^ThreadGroup thread-group ^Runnable f)
        (.setDaemon true)))))
(def cabocha-executor
  (knit/executor :fixed
                 {:num-threads    (.. Runtime getRuntime availableProcessors)
                  :thread-factory (cabocha-thread-factory (knit/thread-group "cabocha-thread"))}))

(defn parse-sentence-synchronized [^String s]
  @(knit/execute cabocha-executor
                 (callable-parse-sentence s)))
