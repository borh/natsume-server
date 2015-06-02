(ns natsume-server.endpoint.interceptors
  (:require [io.pedestal.interceptor.helpers :refer [defbefore defon-request defon-response]]
            [ring.util.response :refer [content-type]]
            [plumbing.core :refer [map-keys ?>]]
            [natsume-server.utils.naming :refer [dashes->lower-camel underscores->dashes]]
            [natsume-server.component.database :as db]
            [natsume-server.config :as config]
            [cheshire.core :as json]
            [io.pedestal.http.body-params :as body-params]))

(def !connection (atom nil))

(defn client-db-connection []
  (if-not @!connection
    (reset! !connection (db/druid-pool (:db config/defaults)))
    @!connection))

;; FIXME no effect??
(defbefore
  utf8-default
  [context]
  (println "defon-request")
  (assoc-in context [:request :character-encoding] "UTF-8"))

(defbefore
  insert-db
  [context]
  (println "insert-db")
  (assoc-in context [:request :conn] (client-db-connection)))

(defon-response
  json-interceptor
  [response]
  (println "json-interceptor")
  (-> response
      (update-in [:body] json/generate-string {:key-fn dashes->lower-camel})
      (content-type "application/json;charset=UTF-8")))

(def custom-body-params
  (body-params/body-params
    (body-params/default-parser-map :json-options {:key-fn underscores->dashes})))

(defon-request
  kebab-case-params
  [request]
  (println "kebab-case-params")
  (update request :query-params (partial map-keys underscores->dashes)))

;; FIXME must run before Swagger coerce-params (this should really be handled by prismatic/schema)
(defbefore
  custom-decode-params
  [context]
  (println "custom-decode-params")
  (update-in context [:request :query-params]
             (fn [m]
               (-> m                                        ;; FIXME Limit should be coerced with schema.
                   (?> (:limit m)   (update :limit   #(try (Long/parseLong %) (catch Exception e -1))))
                   (?> (:genre m)   (update :genre   clojure.string/split #"\."))
                   (?> (:type m)    (update :type    underscores->dashes))
                   (?> (:measure m) (update :measure underscores->dashes))
                   (?> (:norm m)    (update :norm    underscores->dashes))
                   (?> (:html m)    (update :html    (fn [bool] (if (= "true" bool) true false))))))))

(defon-request
  read-body
  [request]
  (println "read-body")
  (update request :body slurp))