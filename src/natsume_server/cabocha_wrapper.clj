(ns natsume-server.cabocha-wrapper
  (:require [clojure.string :as string]
            [flatland.useful.utils :as utils]
            [plumbing.core :refer [?>]]
            [qbits.knit :as knit])
  (:import [org.chasen.cabocha Parser FormatType Token]))

;; # Simple CaboCha JNI Wrapper
;;
;; The CaboCha JNI bindings are not thread-safe, so the parser is wrapped in flatland.useful.utils' thread-local macro.
(defonce parser (utils/thread-local (Parser.)))

(def ^:private unidic-features
  "A vector of all UniDic features, in order, as defined in the UniDic Manual (p. ?) version `2.1.1`."
  [:pos-1 :pos-2 :pos-3 :pos-4 :c-type :c-form :l-form :lemma :orth :pron :orth-base :pron-base :goshu :i-type :i-form :f-type :f-form])

(defn- parse-token [^Token token position surface token-length]
  (let [features  (string/split (.getFeature token) #",")
        ne        (.getNe token)
        token-map (zipmap unidic-features features)]
    (-> token-map
        (assoc :ne    ne
               :begin position
               :end   (+ position token-length))
        (?> (= (count features) 6)
            (assoc
                :lemma     surface
                :orth-base surface
                :pron-base surface ; FIXME translate to katakana.
                :goshu     "不明")))))

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
           (conj parsed {:id chunk-id
                         :link link-id
                         :head head
                         :tail tail
                         :prob prob
                         :tokens tokens})))
        parsed))))


;; FIXME consider core.async ala https://github.com/lynaghk/zmq-async/blob/master/src/com/keminglabs/zmq_async/core.clj

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
                 :num-threads (.. Runtime getRuntime availableProcessors)
                 :thread-factory (cabocha-thread-factory (knit/thread-group "cabocha-thread"))))

(defn parse-sentence-synchronized [^String s]
  @(knit/execute cabocha-executor
                 (callable-parse-sentence s)))
