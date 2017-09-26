(ns natsume-server.component.server
  (:require [mount.core :refer [defstate]]
            [aleph.http :as http]
            [natsume-server.endpoint.api :refer [api-routes]]
            [natsume-server.component.sente :as comm]
            [ring.middleware.defaults :refer [wrap-defaults secure-site-defaults site-defaults]]
            [ring.middleware.json :refer [wrap-json-params]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
            [ring.util.response :as response]
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
    (try
      (if-let [token (auth/get-jwt-token username password)]
        {:status 200
         :body (json/encode {:token token})
         :headers {"Content-Type" "application/json"}}
        {:status 401 :body "invalid credentials"})
      (catch Exception e
        {:status 401
         :body (json/encode (ex-data e))
         :headers {"Content-Type" "application/json"}}))))

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
  (OPTIONS "/api/authenticate" ring-req {:status 200 :body "preflight complete"})
  (GET "/api/auth-files/:filename" [filename :as ring-req]
    (if-not (auth/authfn ring-req (-> ring-req :params :client-id))
      (throw-unauthorized)
      (response/file-response (str "auth-files/" filename))))
  (route/resources "/")
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
               (wrap-defaults (-> (if (= :prod (:profile config))
                                    (assoc secure-site-defaults :proxy true)
                                    site-defaults)
                                  (assoc-in [:security :anti-forgery] false)
                                  (assoc :session false)))
               #_(wrap-anti-forgery {:read-token (fn [request] (get-in request [:headers "x-forgery-token"]))})
               (wrap-json-params)
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
