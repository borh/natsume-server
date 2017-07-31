(ns natsume-server.component.auth
  (:require [natsume-server.config :refer [secrets]]
            [buddy.auth.backends.token :refer [jws-backend]]
            [buddy.sign.jwt :as jwt]
            [cheshire.core :as json]
            [clj-time.core :as time]))

(def secret (:secret secrets))

(defn find-user [username password]
  (some-> (:users secrets)
          (get username)
          (= password)))

(defn get-jwt-token [username password]
  (if (some-> (:users secrets)
              (get username)
              (= password))
    (jwt/sign
     {:user {:id username}
      :exp (time/plus (time/now) (time/hours 6))}
     secret
     {:alg :hs512})))

(defn authfn [request token]
  (try
    (let [decoded-token (jwt/unsign token secret {:alg :hs512})]
      (if (get-in secrets [:users (-> decoded-token :user :id)])
        request))
    (catch Exception e
      {:status 401
       :body (json/encode (ex-data e))
       :headers {"Content-Type" "application/json"}})))

(def backend (jws-backend {:authfn authfn
                           :secret secret
                           :options {:alg :hs512}}))
