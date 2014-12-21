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
            [natsume-server.nlp.error :as error]))

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

(defn get-text-register [conn request]
  ;; FIXME update-in all morphemes all positions with value equal to the end position of the last sentence (or 0 for first sentence) .
  (let [body (->> request :body slurp)]
    (if-let [results (error/get-error conn body)]
      (response (if (:debug (:query-params request))
                  (assoc results :debug {:body body})
                  results))
      (response {:results nil :message "invalid request" :request request}))))

(defn api-endpoint [config] ;; conn is probably not being passed in correctly; look at how we do this in other projects!
  (let [conn (:connection config)]
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
                        (POST "/register" [request] (get-text-register conn request)))))))