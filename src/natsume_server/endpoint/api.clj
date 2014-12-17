(ns natsume-server.endpoint.api
  (:require [compojure.core :refer :all]
            [ring.util.response :refer [response]]
            [clojure.core.reducers :as r]
            [schema.core :as s]
            [plumbing.core :refer [for-map ?>]]

            [natsume-server.component.database :as db]
            [natsume-server.nlp.stats :refer [association-measures-graph]]
            [natsume-server.nlp.text :as text]
            [natsume-server.nlp.annotation-middleware :as anno]
            [natsume-server.nlp.collocations :refer [extract-collocations]]
            [natsume-server.utils.naming :refer [dashes->lower-camel underscores->dashes]]
            ))

;; FIXME move these to upper forms
(def allowed-norms (delay (set (keys @db/!norm-map))))
(def allowed-types db/!gram-types)
(def allowed-measures (set (conj (keys association-measures-graph) :count)))

(s/defn view-sources-api [request]
  (response "TODO"))

(s/defn view-sources-genre
  "Returns a JSON d3-compatible tree structure of counts by genre.
  Defaults to sources count.
  Checks for valid input, defined as what is available in db/norm-map."
  [request]
  (response ((or (->> request :query-params :norm keyword (get @allowed-norms))
                    :sources)
                @db/!norm-map)))

(s/defn view-genre-similarity
  [request]
  (if-let [genre (-> request :query-params :genre (clojure.string/split #"\."))]
    (response :TODO #_(lm/get-genre-similarities genre))))

(defn view-tokens [conn {:keys [query-params]}]
  (let [{:keys [genre norm] :or {norm :tokens}} query-params] ; TODO :norm should include other measures like tf-idf, dice, etc.
    ;; FIXME actually, norm should not be settable, but only indirectly available through other measures like tf-idf, etc.
    (println query-params)
    (response (db/get-one-search-token conn query-params :norm (keyword norm)))))

(defn view-sentences-by-collocation [conn {:keys [query-params]}]
  ;; validate: html sort order
  (response (db/query-sentences conn query-params)))


(defn view-collocations-tree [conn {:keys [query-params]}]
  (let [query-params (merge
                      {:compact-numbers true
                       :scale true}
                      query-params)]
    (if-let [r (db/query-collocations-tree conn query-params)]
      (response (if (:debug query-params)
                     {:results r
                      :query query-params}
                     r)))))

(defn score-sentence [conn tree sentence]
  (let [tokens (->> tree
                    (mapcat :tokens)
                    (remove (fn [{:keys [pos pos-1 pos-2]}] (or (= pos :symbol) (and (= pos-1 "助詞") (or (= pos-2 "格助詞") (= pos-2 "係助詞"))))))
                    (pmap (fn [m]
                            (let [register-score (db/token-register-score m)
                                  response {:type :token
                                            :tags (:tags m)
                                            :pos (:pos m)
                                            :begin (:begin m)
                                            :end (:end m)
                                            :lemma (:lemma m)
                                            :string (:orth m)}]
                              (if (map? register-score)
                                (merge response register-score)
                                response))))
                    (into []))
        collocations (->> tree
                          extract-collocations
                          (filter identity) ;; FIXME ぞれ NPE problem
                          (remove (fn [m] (= (:type m) [:verb :verb]))) ;; FIXME
                          (pmap (fn [m]
                                  (let [record
                                        {:type :collocation
                                         :pos  (:type m)
                                         :tags (:tags m)
                                         :parts (->> m
                                                     :data
                                                     (r/map (fn [part]
                                                              (let [begin (or (:head-begin part) (:tail-begin part))
                                                                    end   (or (:head-end part)   (:tail-end part))
                                                                    pos   (or (:head-pos part)   (:tail-pos part))
                                                                    tags  (or (:head-tags part)  (:tail-tags part))
                                                                    lemma (or (:head-string part) (:tail-string part))]
                                                                {:begin begin
                                                                 :end end
                                                                 :pos pos
                                                                 :tags tags
                                                                 :lemma lemma
                                                                 :string (subs sentence begin end) #_(:head-string part) #_(:tail-string part)})))
                                                     (into []))}
                                        register-score (db/collocation-register-score conn m)]
                                    (-> record
                                        (assoc :string (clojure.string/join (map :string (:parts record))))
                                        (?> (map? register-score) (merge register-score))))))
                          (into []))]
    (concat tokens collocations)))
(defn get-text-register [conn request]
  ;; FIXME update-in all morphemes all positions with value equal to the end position of the last sentence (or 0 for first sentence) .
  (let [body (->> request :body slurp)]
    (if-let [paragraphs (->> body vector text/lines->paragraph-sentences)]
      (let [update-positions (fn [m offset] (-> m (update-in [:begin] + offset) (update-in [:end] + offset)))

            [scored-sentences parsed-tokens]
            (loop [ss (for [paragraph paragraphs sentence paragraph] sentence)
                   offset 0
                   parsed-tokens []
                   results []]
              (if-let [s (first ss)]
                (let [tree (anno/sentence->tree s)
                      token-seq (mapv #(select-keys % [:orth :orth-base :lemma :pos-1 :pos-2 :c-form :c-type :tags]) (mapcat :tokens tree))
                      scored-s (score-sentence conn tree s)
                      length-s (count s)
                      new-offset (+ offset length-s)
                      nl? (and (< new-offset (count body)) (= \newline (first (subs body new-offset (inc new-offset)))))]
                  (recur (next ss)
                         (+ new-offset (if nl? 1 0))
                         (concat parsed-tokens (if nl? (conj token-seq {:orth "\n" :orth-base "\n" :lemma "\n" :pos-1 "補助記号" :pos-2 "*" :c-form "*" :c-type "*"}) token-seq))
                         (concat results
                                 (map (fn [m] (case (:type m)
                                               :token (update-positions m offset)
                                               :collocation (update-in m [:parts]
                                                                       (fn [parts]
                                                                         (mapv (fn [part]
                                                                                 (update-positions part offset))
                                                                               parts)))))
                                      scored-s))))
                [results (vec parsed-tokens)]))

            bad-morphemes (->> scored-sentences
                               (r/filter #(or (:register-score %) (:stats %) #_(and (:stats %) #_(> (-> % :stats :mi) 5.0))))
                               (into []))]
        (response (if (:debug (:query-params request))
                       {:results bad-morphemes :parsed-tokens parsed-tokens :debug {:body body :parsed paragraphs}}
                       {:results bad-morphemes :parsed-tokens parsed-tokens})))
      (response {:results nil :message "invalid request" :request request}))))

(defn api-endpoint [conn config] ;; conn is probably not being passed in correctly; look at how we do this in other projects!
  (routes
    (context "/api" []
             (context "/sources" []
                      (GET "/" [] view-sources-api)
                      (context "/genre" []
                               (GET "/" [request] (view-sources-genre conn request)
                                    (GET "/similarity" [request] (view-genre-similarity conn request)))))
             (context "/tokens" []
                      (GET "/" [request] (view-tokens conn request)))
             (context "/sentences" []
                      (GET "/collocations" [request] (view-sentences-by-collocation conn request)))
             (context "/collocations" []
                      (GET "/tree" [request] (view-collocations-tree conn request)))
             (context "/errors" []
                      (POST "/register" [request] (get-text-register conn request))))))