(ns natsume-server.cabocha-wrapper
  (:require [clojure.string :as string])
  (:import [org.chasen.cabocha Parser FormatType Token]))

;; # Simple CaboCha JNI Wrapper
;;
;; The CaboCha JNI bindings should be used per-thread.
;; The *parser* dynamic var and with-new-parser macro can be used for
;; concurrent/parallel processing.
;;
;; ## TODO
;;
;; -   agent pool of parsers? (how to handle locking/race behaviour?)
(defonce ^:dynamic *parser* (Parser.))

(defmacro with-new-parser [& body]
  `(binding [*parser* (Parser.)]
     (do ~@body)))

(def ^:private unidic-features
  "A vector of all UniDic features, in order, as defined in the UniDic Manual (p. ?) version `2.1.1`."
  [:pos1 :pos2 :pos3 :pos4 :cType :cForm :lForm :lemma :orth :pron :orthBase :pronBase :goshu :iType :iForm :fType :fForm])

(defn- parse-token [^Token token position surface token-length]
  (let [features  (string/split (.getFeature token) #",")
        ne        (.getNe token)
        token-map (zipmap unidic-features features)]
    (if (= (count features) 6)
      (assoc token-map
        :lemma    surface
        :orthBase surface
        :goshu    "不明"
        :ne       ne
        :begin    position
        :end      (+ position token-length))
      (assoc token-map
        :ne    ne
        :begin position
        :end   (+ position token-length)))))

(defn- parse-tokens [tokens position]
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

(defn parse-sentence
  "Parses input sentence string and returns a vector of CaboCha chunks containing dependency
   information and a vector of tokens, which are represented as maps.

   TODO consider deftype/benchmark"
  [^String s]
  (let [tree (.parse ^Parser *parser* s)
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
           (conj parsed {:id chunk-id
                         :link link-id
                         :head head
                         :tail tail
                         :prob prob
                         :tokens tokens})))
        parsed))))
