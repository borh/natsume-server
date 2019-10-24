(ns natsume-server.nlp.cabocha-wrapper
  (:require [clojure.string :as string]
            [qbits.knit :as knit]
            [clojure.spec.alpha :as s]
            [clj-mecab.parse :as mecab]
            [taoensso.timbre :as timbre])
  (:import [org.chasen.cabocha Parser Token Tree]
           [clojure.lang IDeref]))

;; # Simple CaboCha JNI Wrapper

(defn thread-local*
  [init]
  (let [generator (proxy [ThreadLocal] []
                    (initialValue [] (init)))]
    (reify IDeref
      (deref [this]
        (.get generator)))))

(defonce parser (thread-local* (fn [] (Parser.))))

(defonce version (Parser/version))

(defonce mecab-version "0.996")

;; We remap to more sensible namespace for this project.
(def mecab-features (map #(keyword "morpheme" (name %)) mecab/*features*))

(s/def :mecab/morpheme
  (s/keys :req [:morpheme/pos-1 :morpheme/pos-2 :morpheme/pos-3 :morpheme/pos-4 :morpheme/c-type :morpheme/c-form :morpheme/l-form :morpheme/lemma :morpheme/orth :morpheme/pron :morpheme/orth-base :morpheme/pron-base :morpheme/goshu :morpheme/i-type :morpheme/i-form :morpheme/f-type :morpheme/f-form :morpheme/i-con-type :morpheme/f-con-type :morpheme/type :morpheme/kana :morpheme/kana-base :morpheme/form :morpheme/form-base :morpheme/a-type :morpheme/a-con-type :morpheme/a-mod-type :morpheme/lid :morpheme/lemma-id]))

;; ADJ – ADP – ADV – AUX – CCONJ – DET – INTJ – NOUN – NUM – PART – PRON – PROPN – PUNCT – SCONJ – SYM – VERB – X
(s/def :pos/ud #{:adj :adp :adv :aux :cconj :det :intj :noun :num :part :pron :propn :punct :sconj :sym :verb :x})
(s/def :pos/natsume #{:verb :noun :adverb :adjective :preposition
                      :suffix :prefix :utterance :auxiliary-verb :symbol
                      :particle})
(s/def :morpheme/pos :pos/natsume)                          ;; TODO switchable
(s/def :morpheme/ne (s/nilable keyword?))
(s/def :morpheme/begin int?)
(s/def :morpheme/end int?)
(s/def :morpheme/tag #{:dekiru :iru :simau :kuru :morau :ageru
                       :negative :teido :you :hoshii :ii :sou-dengon
                       :potential :past :aspect-ku
                       :passive :active :polite :tari})
(s/def :morpheme/tags (s/nilable (s/coll-of :morpheme/tag)))

(s/def :sentence/morpheme
  (s/merge :mecab/morpheme
           (s/keys :opt [:morpheme/pos :morphemes/ne
                         :morpheme/begin :morpheme/end
                         :morpheme/tags])))

(s/def :chunk/id int?)
(s/def :chunk/link int?)
(s/def :chunk/head int?)
(s/def :chunk/tail int?)
(s/def :chunk/prob float?)
(s/def :chunk/tokens (s/coll-of :sentence/morpheme))
(s/def :cabocha/chunk
  (s/keys :req [:chunk/id :chunk/link :chunk/prob :chunk/tokens]
          :opt [:chunk/head :chunk/tail]))

(s/def :sentence/chunk
  (s/merge :cabocha/chunk
           (s/keys
             :opt [:chunk/head-string :chunk/head-orth :chunk/head-begin :chunk/head-end
                   :chunk/head-pos :chunk/head-tags :chunk/head-begin-index :chunk/head-end-index
                   :chunk/tail-string :chunk/tail-orth :chunk/tail-begin :chunk/tail-end
                   :chunk/tail-pos :chunk/tail-tags :chunk/tail-begin-index :chunk/tail-end-index])))

(s/fdef recode-pos
  :args (s/cat :m :sentence/morpheme)
  :ret :morpheme/pos)

(defn recode-pos
  [m]
  (condp re-seq (str (:morpheme/pos-1 m) (:morpheme/pos-2 m) (:morpheme/pos-3 m))
    #"^動詞" :verb
    #"^(副詞|名詞.+副詞可能)" :adverb
    #"^(代?名詞[^副]+|記号文字)" :noun
    #"^(形(容|状)詞|接尾辞形(容|状)詞的)" :adjective
    #"^助詞" (if (or (and (= "接続助詞" (:pos-2 m))
                        (re-seq #"^(て|ば)$" (:morpheme/lemma m)))
                   (re-seq #"^たり$" (:morpheme/lemma m)))
             :auxiliary-verb
             :particle)
    #"^接続詞" :particle
    #"^((補助)?記号|空白)" :symbol
    #"^助動詞" (if (re-seq #"^助動詞-(ダ|デス)$" (:morpheme/c-type m))
              :particle
              :auxiliary-verb)
    #"^連体詞" :preposition
    #"^感動詞" :utterance                                      ; :INTJ
    #"^接頭辞" :prefix                                         ; :NOUN
    #"^接尾辞" (cond (= (:pos-2 m) "動詞的") :adjective           ;; ~がかった
                  (= (:pos-2 m) "名詞的") :noun                ;; ~ら
                  :else :suffix)
    :unknown-pos))                                          ;; Catchall, but should not happen, so not speced.

(s/fdef parse-token
  :args (s/cat :token :cabocha/token :position int? :surface string? :token-length int?)
  :ret :sentence/morpheme)

(defn parse-token
  [^Token token position surface token-length]
  (let [features (string/split (.getFeature token) #",")
        ne (.getNe token)
        token-map (-> mecab-features
                      (zipmap features)
                      (assoc :morpheme/orth surface
                             :morpheme/ne ne
                             :morpheme/begin position
                             :morpheme/end (+ position token-length)))
        token-map (assoc token-map :morpheme/pos (recode-pos token-map))]
    (if (= (count features) 6)
      (assoc token-map
        :morpheme/lemma surface
        :morpheme/orth-base surface
        :morpheme/orth-base surface                         ; FIXME translate to katakana.
        :morpheme/goshu "不明")
      token-map)))

(s/fdef parse-tokens
  :args (s/cat :chunk/tokens (s/coll-of :cabocha/token) :position int?)
  :ret (s/coll-of :sentence/morpheme))

(defn parse-tokens
  [tokens position]
  (loop [tokens* tokens
         position* position
         parsed []]
    (if-let [^Token token (first tokens*)]
      (let [token-surface (.getSurface token)
            token-length (count token-surface)]
        (recur (rest tokens*)
               (+ position* token-length)
               (conj parsed (parse-token token position* token-surface token-length))))
      parsed)))

(s/fdef parse-sentence
  :args (s/cat :s string?)
  :ret (s/coll-of :sentence/chunk))

(defn parse-sentence
  "Parses input sentence string and returns a vector of CaboCha chunks containing dependency
   information and a vector of tokens, which are represented as maps."
  [^String s]
  (let [^Tree tree (.parse ^Parser @parser s)
        chunks (.chunk_size tree)]
    (loop [chunk-id 0
           token-id 0
           position 0
           parsed []]
      (if (< chunk-id chunks)
        (let [chunk (.chunk tree chunk-id)
              link-id (.getLink chunk)
              prob (.getScore chunk)
              head (.getHead_pos chunk)
              tail (.getFunc_pos chunk)
              token-count (.getToken_size chunk)
              token-list (mapv #(.token tree %) (range token-id (+ token-id token-count)))
              tokens (parse-tokens token-list position)]
          (recur
            (inc chunk-id)
            (+ token-id token-count)
            (int (:morpheme/end (last tokens)))
            (conj parsed
                  #:chunk{:id     chunk-id
                          :link   link-id
                          :head   head
                          :tail   tail
                          :prob   prob
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

(defn create-executor []
  ^java.util.concurrent.ExecutorService
  (knit/executor :fixed
                 {:num-threads    (let [cores (.. Runtime getRuntime availableProcessors)]
                                    ;; CaboCha is a memory expensive process, so we limit to 6.
                                    (if (>= cores 6) 6 cores))
                  :thread-factory (cabocha-thread-factory (knit/thread-group "cabocha-thread"))}))

(def cabocha-executor (create-executor))

(defn parse-sentence-synchronized [^String s]
  @(knit/execute cabocha-executor
                 (callable-parse-sentence s)))

(defn reset-threadpool! []
  (timbre/info {:cabocha-executor-state :running :info cabocha-executor})
  (try
    (.shutdownNow cabocha-executor)
    (catch Exception e (println e)))
  (alter-var-root #'natsume-server.nlp.cabocha-wrapper/cabocha-executor
                  (fn [_] (create-executor)))
  (timbre/info {:cabocha-executor-state :restarted :info cabocha-executor}))