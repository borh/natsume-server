(ns natsume-server.server
  (:use [ring.adapter.jetty :only (run-jetty)]
        [compojure.core :only (defroutes GET POST)]
        [ring.middleware.format-params :only (wrap-restful-params)]
        [ring.middleware.format-response :only (wrap-restful-response)])
  (:require [natsume-server.core :as core]
            [natsume-server.cabocha-wrapper :as cw]
            [compojure.route :as route]
            [compojure.handler :as handler]))

(defn split-input
  [text]
  (core/string->paragraph->lines text))

(defn transform-sentences
  [text]
  (->> text
       (map #(map cw/string-to-tree %))))

(defn analyze-input
  [text]
  (-> text
      core/string->paragraph->lines
      transform-sentences))

;; FIXME somehow compojure-style destructuring does not work so we deal with the request map directly
(defroutes app*
  (GET  "/split"   request (split-input (:query-string request)))
  (POST "/split"   request (split-input (slurp (:body request))))
  (GET  "/analyze" request (analyze-input (:query-string request)))
  (POST "/analyze" request (analyze-input (slurp (:body request))))
  (route/not-found "Sorry, there's nothing here."))

(def app (-> app*
             handler/api
             wrap-restful-params
             wrap-restful-response))

(defonce server (run-jetty #'app {:port 9000 :join? false}))
