(ns natsume-server.component.server
  (:require [mount.core :refer [defstate]]
            [bidi.ring :refer [make-handler]]
            [aleph.http :as http]
            [natsume-server.endpoint.api :refer [api-routes]]
            [natsume-server.config :refer [config]]))

(defstate server
  :start (http/start-server
          (make-handler api-routes)
          {:port (-> config :http :port)
           :raw-stream? true})
  :stop (.close server))
