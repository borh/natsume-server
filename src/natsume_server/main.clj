(ns natsume-server.main
  (:gen-class)
  (:require ;;[user :as user]
            [natsume-server.config :refer [run-mode config]]
            [natsume-server.component.database]
            [natsume-server.component.server]
            [natsume-server.component.load]
            [natsume-server.endpoint.api]
            [mount.core :as mount]))

(defn -main [& args]
  #_(user/start)
  (mount/start #'natsume-server.config/run-mode
               #'natsume-server.config/config

               #'natsume-server.component.database/connection
               #'natsume-server.component.database/database-init)

  (when (:server config)
    (mount/start
     #'natsume-server.component.database/!norm-map
     #'natsume-server.component.database/!genre-names
     #'natsume-server.component.database/!genre-tokens-map
     #'natsume-server.component.database/!gram-totals
     #'natsume-server.component.database/!gram-types
     #'natsume-server.component.database/!tokens-by-gram

     #'natsume-server.component.server/server)
    )

  (when (:process config)
    (mount/start
     #'natsume-server.component.load/data))

  (when (:server config)
    (mount/start
     #'natsume-server.endpoint.api/api-routes))
  #_(mount/start #'natsume-server.config/run-mode
               #'natsume-server.config/config

               #'natsume-server.component.database/connection
               #'natsume-server.component.database/database
               #'natsume-server.component.database/!norm-map
               #'natsume-server.component.database/!genre-names
               #'natsume-server.component.database/!genre-tokens-map
               #'natsume-server.component.database/!gram-totals
               #'natsume-server.component.database/!gram-types
               #'natsume-server.component.database/!tokens-by-gram

               #'natsume-server.component.server/server
               #'natsume-server.component.load/data

               #'natsume-server.endpoint.api/api-routes))
