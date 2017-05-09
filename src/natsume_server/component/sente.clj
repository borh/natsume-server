(ns natsume-server.component.sente
  (:require [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.aleph :refer (get-sch-adapter)]
            [taoensso.sente.packers.transit :as sente-transit]
            [taoensso.timbre :as timbre :refer [info]]
            [mount.core :refer [defstate]]
            [clojure.string :as str]
            [natsume-server.component.database :as db]
            [natsume-server.component.query :as q]
            [natsume-server.nlp.word2vec :as word2vec]
            [natsume-server.nlp.annotation-middleware :as anno]
            [natsume-server.nlp.topic-model :as topic-model]
            [natsume-server.nlp.error :as error]))

(defmulti event-msg-handler :id)

(defmethod event-msg-handler :default
  [{:as ev-msg :keys [event id client-id ?data request ?reply-fn send-fn]}]
  ;; no session/uid with current config
  (let [session (:session request)
        uid     (:uid session)]
    (println (str client-id " -> unknown event: " event "; " {:session session :uid uid}))
    (when ?reply-fn
      (?reply-fn {:unknown-event event}))))

(defmethod event-msg-handler :chsk/ws-ping [_] (print "."))

(defmethod event-msg-handler :chsk/uidport-open
  [{:keys [client-id request]}]
  (let [session (:session request)
        uid     (:uid session)]
    (println (str client-id " -> uidport open; " {:session session :uid uid}))))

(defmethod event-msg-handler :chsk/uidport-close
  [{:keys [client-id request]}]
  (let [session (:session request)
        uid     (:uid session)]
    (println (str client-id " -> uidport closed; " {:session session :uid uid}))))

(defn init-channel! []
  (let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
                connected-uids]}
        (sente/make-channel-socket-server!
         (get-sch-adapter)
         {:packer (sente-transit/get-transit-packer)})]
    {:ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn
     :ring-ajax-post-fn ajax-post-fn
     :connected-uids connected-uids
     :ch-recv ch-recv
     :send-fn send-fn
     :router (sente/start-chsk-router! ch-recv event-msg-handler)}))

(defstate channel
  :start (init-channel!)
  :stop ((:router channel)))

;; API

(defmethod event-msg-handler :sources/genre
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (?reply-fn (?data db/!norm-map)))

(defn with-time-duration
  "Returns a map wrapping the result in :result and time elapsed in :duration (in seconds)."
  [expr]
  (let [start (. System (nanoTime))
        result (expr)]
    {:duration (/ (double (- (. System (nanoTime)) start)) 1000000000.0)
     :result result}))

(defmethod event-msg-handler :fulltext/matches
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (?reply-fn (q/query-fulltext db/connection ?data)))

(defmethod event-msg-handler :sources/sentence-id
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (?reply-fn (q/query-expanded-document db/connection ?data)))

(defmethod event-msg-handler :sentences/collocations
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (?reply-fn (q/query-sentences db/connection ?data)))

(defmethod event-msg-handler :sentences/tokens
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (?reply-fn (q/query-sentences-tokens db/connection ?data)))

(defmethod event-msg-handler :tokens/tree
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (?reply-fn (q/get-one-search-token db/connection ?data)))

(defmethod event-msg-handler :tokens/similarity
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (?reply-fn (apply word2vec/similarity ?data)))

(defmethod event-msg-handler :tokens/nearest-tokens
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (?reply-fn (apply word2vec/token-nearest-tokens ?data)))

(defmethod event-msg-handler :tokens/similarity-with-accuracy
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (?reply-fn (apply word2vec/similar-tokens-with-accuracy ?data)))

(defmethod event-msg-handler :tokens/tsne
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [m-fn (memoize (fn [x] (apply word2vec/tsne x)))]
    (?reply-fn (m-fn ?data))))

(defmethod event-msg-handler :collocations/collocations
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (?reply-fn (q/query-collocations db/connection ?data)))

(defmethod event-msg-handler :collocations/tree
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (?reply-fn (q/query-collocations-tree db/connection ?data)))

(defmethod event-msg-handler :errors/register
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (?reply-fn (error/get-error db/connection ?data)))

(defmethod event-msg-handler :suggestions/tokens
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (?reply-fn (->> (db/q db/connection
                        {:select [:orth-base :pos-1]
                         :modifiers [:distinct]
                         :from [:search-tokens]
                         :group-by [:lemma :orth-base :pos-1]
                         :where [:= :lemma (:lemma ?data)]})
                  (map (fn [m]
                         (assoc m :score
                                (->> m
                                     (q/get-one-search-token db/connection)
                                     (error/sigma-score :default-pos 1)
                                     :register-score
                                     :good))))
                  (sort-by :score >))))

(defmethod event-msg-handler :topics/infer
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [text-tokens (->> (:text ?data)
                         (anno/sentence->cabocha)
                         (mapcat :tokens)
                         (map (first (:features ?data))) ;; TODO Also, unit-type.
                         (str/join " "))]
    (?reply-fn (topic-model/make-prediction (:unit-type ?data) (:features ?data) text-tokens))))
