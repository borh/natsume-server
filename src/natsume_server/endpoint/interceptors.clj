(ns natsume-server.endpoint.interceptors
  (:require [io.pedestal.interceptor :refer [interceptor]]
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

(def utf8-default
  (interceptor
    {:name  ::utf8-default
     :enter (fn [context]
              (assoc-in context [:request :character-encoding] "UTF-8"))}))

(def insert-db
  (interceptor
    {:name  ::insert-db
     :enter (fn [context]
              (assoc-in context [:request :conn] (client-db-connection)))}))

(def json-response
  (interceptor
    {:name  ::json-interceptor
     :leave (fn [context]
              (-> context
                  (update-in [:response :body] json/generate-string {:key-fn dashes->lower-camel})
                  (content-type "application/json;charset=UTF-8")))}))

(def custom-body-params
  (body-params/body-params
    (body-params/default-parser-map :json-options {:key-fn underscores->dashes})))

(def kebab-case-params
  (interceptor
    {:name  ::kebab-case-params
     :enter (fn [context]
              (update-in context [:request :query-params] (partial map-keys underscores->dashes)))}))

;; FIXME must run before Swagger coerce-params (this should really be handled by prismatic/schema)
(def custom-decode-params
  (interceptor
    {:name  ::custom-decode-params
     :enter (fn [context]
              (update-in
                context
                [:request :query-params]
                (fn [m]
                  (-> m                                     ;; FIXME Limit should be coerced with schema.
                      (?> (:limit m) (update :limit #(try (Long/parseLong %) (catch Exception e -1))))
                      (?> (:genre m) (update :genre clojure.string/split #"\."))
                      (?> (:type m) (update :type underscores->dashes))
                      (?> (:measure m) (update :measure underscores->dashes))
                      (?> (:norm m) (update :norm underscores->dashes))
                      (?> (:html m) (update :html (fn [bool] (if (= "true" bool) true false))))))))}))

(def read-body
  (interceptor
    {:name  ::read-body
     :enter (fn [context]
              (update-in context [:request :body] slurp))}))