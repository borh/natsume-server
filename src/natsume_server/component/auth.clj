(ns natsume-server.component.auth
  (:require [natsume-server.config :refer [secrets]]
            [buddy.auth.backends.token :refer [jws-backend]]))

(def secret (:secret secrets))
(def backend (jws-backend {:secret secret :options {:alg :hs512}}))

(defn find-user [username password]
  (get-in secrets [:users username password]))
