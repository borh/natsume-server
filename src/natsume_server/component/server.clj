(ns natsume-server.component.server
  (:require [mount.core :refer [defstate]]
            [bidi.ring :refer [make-handler]]
            [aleph.http :as http]
            [natsume-server.endpoint.api :refer [api-routes]]
            [natsume-server.component.sente :as comm]
            [ring.middleware.defaults]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [natsume-server.config :refer [config]]))


(defroutes ring-routes
  (GET  "/api/chsk"  ring-req ((:ring-ajax-get-or-ws-handshake comm/channel) ring-req))
  (POST "/api/chsk"  ring-req ((:ring-ajax-post-fn comm/channel) ring-req))
  (route/resources "/") ; Static files, notably public/main.js (our cljs target)
  (route/not-found "<h1>Page not found</h1>"))

(defstate server
  :start (when (:server config)
           (let [s (http/start-server
                    (ring.middleware.defaults/wrap-defaults
                     ring-routes ring.middleware.defaults/site-defaults)
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
