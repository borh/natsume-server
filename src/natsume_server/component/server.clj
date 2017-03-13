(ns natsume-server.component.server
  (:require [mount.core :refer [defstate]]
            [aleph.http :as http]
            [natsume-server.endpoint.api :refer [api-routes]]
            [natsume-server.component.sente :as comm]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.logger.timbre :as logger]
            [buddy.auth :refer [authenticated? throw-unauthorized]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [natsume-server.component.auth :as auth]
            [cheshire.core :as json]
            [compojure.core :refer [defroutes routes GET POST OPTIONS]]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [natsume-server.config :refer [config]]))

(defn create-authentication-token
  [request]
  (let [{:keys [username password]} (:params request)]
    (if-let [token (auth/get-jwt-token username password)]
      {:status 200
       :body (json/encode {:token token})
       :headers {"Content-Type" "application/json"}}
      {:status 400 :body "invalid credentials"})))

(defroutes ring-routes
  (GET "/api/chsk" ring-req
    (if-not (auth/authfn ring-req (-> ring-req :params :client-id))
      (throw-unauthorized)
      ((:ring-ajax-get-or-ws-handshake comm/channel) ring-req)))
  (POST "/api/chsk" ring-req
    (if-not (auth/authfn ring-req (-> ring-req :params :client-id))
      (throw-unauthorized)
      ((:ring-ajax-post-fn comm/channel) ring-req)))
  (POST "/api/authenticate" ring-req
    (create-authentication-token ring-req))
  (GET "/api/authenticate" ring-req
    (create-authentication-token ring-req))
  (OPTIONS "/api/authenticate" ring-req
           (create-authentication-token ring-req))
  (route/resources "/") ; Static files, notably public/main.js (our cljs target)
  (route/not-found "<h1>Page not found</h1>"))

(defstate server
  :start (when (:server config)
           (-> ring-routes
               (routes)
               (logger/wrap-with-logger)
               (wrap-cors
                :access-control-allow-origin (->> config :http :access-control-allow-origin (mapv re-pattern))
                :access-control-allow-methods [:get :post :options]
                :access-control-allow-headers ["Content-Type"])
               (wrap-authentication auth/backend)
               (wrap-authorization auth/backend)
               (wrap-defaults site-defaults)
               (handler/site)
               (http/start-server {:port (-> config :http :port)})))
  :stop (when (:server config)
          (.close server)))

(comment
  (defstate server
    :start (when (:server config)
             (http/start-server
              (make-handler api-routes)
              {:port (-> config :http :port)
               :raw-stream? true}))
    :stop (when (:server config)
            (.close server))))
