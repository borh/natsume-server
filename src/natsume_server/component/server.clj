(ns natsume-server.component.server
  (:require [mount.core :refer [defstate]]
            [taoensso.timbre :as timbre]
            [ring.adapter.jetty :as jetty]
            [compojure.core :refer [defroutes GET POST context]]
            [compojure.route :as route]
            [muuntaja.middleware :as mw]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.webjars :refer [wrap-webjars]]
            [natsume-server.component.transit :as transit]
            [natsume-server.config :refer [config]]
            [natsume-web.bulma-ui :as ui]
            [natsume-server.utils.fs :as fs]))

(def index-compiled
  (ui/page {:author      "Bor Hodošček/Hinoki Project"
            :description "Cypress Fulltext Search"
            :title       "Cypress Fulltext Search"
            :app-name    "Cypress Fulltext Search"
            :lang        "en"}))

(defn api-handler [request]                                 ;; FIXME errors should be transit too!
  (if-let [body (:body-params request)]
    (let [[event data] body]
      (try (if-let [response (transit/process-request event data)]
             {:status 200
              :body   response}
             {:status 405 :body request})
           (catch org.postgresql.util.PSQLException e
             {:status 500
              :body   {:error/message (str "SQL " (.getMessage e))}})))
    {:status 400 :body request}))

(defroutes
  web-router
  (context "/natsume-search" []
    (GET "/" [] {:status 301 :headers {"Location" "fulltext"}})
    (GET "/index.html" [] {:status 301 :headers {"Location" "fulltext"}})
    (GET "/api" request (api-handler request))
    (POST "/api" request (api-handler request))
    (GET "/fulltext" []
      {:status  200
       :headers {"Content-Type" "text/html"}
       :body    index-compiled})
    (context "/cache/files" [] (wrap-file (route/not-found "File Not Found") (str fs/tmp-path)))
    (route/resources "/public")
    (route/not-found "Not Found"))
  (route/not-found "Not Found"))

(def app
  (-> web-router
      mw/wrap-format
      (wrap-webjars "/natsume-search/assets")
      (wrap-resource "/resources/public")
      (wrap-content-type)
      (wrap-not-modified)))

(defstate server
          :start (when (:server config)
                   (jetty/run-jetty #'app {:join? false :port (-> config :http :port)}))
          :stop (when (:server config)
                  (.stop server)))
