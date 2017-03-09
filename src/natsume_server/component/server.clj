(ns natsume-server.component.server
  (:require [mount.core :refer [defstate]]
            [bidi.ring :refer [make-handler]]
            [aleph.http :as http]
            [natsume-server.endpoint.api :refer [api-routes]]
            [natsume-server.component.sente :as comm]
            [ring.middleware.defaults]
            [buddy.auth :refer [authenticated? throw-unauthorized]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [buddy.sign.jwt :as jwt]
            [clj-time.core :as time]
            [natsume-server.component.auth :as auth]
            [cheshire.core :as json]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [natsume-server.config :refer [config]]))

(defn login-handler
  [request]
  (let [data (:form-params request)
        user (auth/find-user (:username data)
                             (:password data))]
    (if user
      (let [claims {:user user
                    :exp (time/plus (time/now) (time/minutes 10))}
            token (jwt/sign claims auth/secret {:alg :hs512})]
        {:status 200
         :body (json/encode {:token token})
         :headers {:content-type "application/json"}})
      {:status 400 :body "invalid credentials"})))

(defroutes ring-routes
  (GET  "/api/chsk"  ring-req
    (if-not (authenticated? ring-req)
      (throw-unauthorized)
      ((:ring-ajax-get-or-ws-handshake comm/channel) ring-req)))
  (POST "/api/chsk"  ring-req
    (if-not (authenticated? ring-req)
      (throw-unauthorized)
      ((:ring-ajax-post-fn comm/channel) ring-req)))
  (POST "/api/authenticate" ring-req
    (login-handler ring-req))
  (route/resources "/") ; Static files, notably public/main.js (our cljs target)
  (route/not-found "<h1>Page not found</h1>"))

(defstate server
  :start (when (:server config)
           (let [s (http/start-server
                    (-> ring-routes
                        (wrap-authorization auth/backend)
                        (wrap-authentication auth/backend)
                        (ring.middleware.defaults/wrap-defaults
                         ring.middleware.defaults/site-defaults))
                    {:port (-> config :http :port)
                     :raw-stream? true})
                 p (promise)]
             (future @p)
             (fn []
               (.close ^java.io.Closeable s)
               (deliver p nil))))
  :stop (when (:server config)
          (server)))

(comment
  (defstate server
    :start (when (:server config)
             (http/start-server
              (make-handler api-routes)
              {:port (-> config :http :port)
               :raw-stream? true}))
    :stop (when (:server config)
            (.close server))))
