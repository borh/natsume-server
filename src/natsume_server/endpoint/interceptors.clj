(ns natsume-server.endpoint.interceptors
  (:require [io.pedestal.interceptor :refer [defbefore defon-request defon-response]]
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
(defon-request
  utf8-default
  [request]
  (assoc-in request [:character-encoding] "UTF-8"))

(defbefore
  insert-db
  [context]
  (assoc-in context [:request :conn] (client-db-connection)))

(defon-response
  json-interceptor
  [response]
  (-> response
      (update-in [:body] json/generate-string {:key-fn dashes->lower-camel})
      (content-type "application/json;charset=UTF-8")))

(def custom-body-params
  (body-params/body-params
    (body-params/default-parser-map :json-options {:key-fn underscores->dashes})))

(defon-request
  kebab-case-params
  [request]
  (update request :query-params (partial map-keys underscores->dashes)))

;; FIXME must run before Swagger coerce-params
(defbefore
  custom-decode-params
  [context]
  (update-in context [:request :query-params]
             (fn [m]
               (-> m                                        ;; FIXME Limit should be coerced with schema.
                   (?> (:limit m)   (update :limit   #(try (Long/parseLong %) (catch Exception e -1))))
                   (?> (:genre m)   (update :genre   clojure.string/split #"\."))
                   (?> (:type m)    (update :type    underscores->dashes))
                   (?> (:measure m) (update :measure underscores->dashes))))))

(defbefore
  read-body
  [context]
  (update-in context [:request :body]
             slurp))