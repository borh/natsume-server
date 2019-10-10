(ns natsume-server.component.auth
  (:require [natsume-server.config :refer [secrets]]
            [buddy.auth :as auth]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.backends.token :refer [jws-backend]]
            [taoensso.timbre :refer [error]]
            [ring.util.http-response :as response]
            [clj-time.core :as time]
            [buddy.sign.jwt :as jwt]))

;; https://github.com/funcool/buddy-auth/blob/master/examples/session/src/authexample/web.clj

;; https://github.com/metosin/reitit/issues/185

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
       :exp  (time/plus (time/now) (time/hours 6))}
      secret
      {:alg :hs512})))

(defn authfn [request token]
  (let [decoded-token (jwt/unsign token secret {:alg :hs512})]
    #_(error (str "decoded token" decoded-token))
    (if (get-in secrets [:users (-> decoded-token :user :id)])
      request)))

(def token-backend (jws-backend {:authfn  authfn
                                 :secret  secret
                                 :options {:alg :hs512}}))

#_(defn unauthorized-handler
  [request metadata]
  (cond
    ;; If request is authenticated, raise 403 instead
    ;; of 401 (because user is authenticated but permission
    ;; denied is raised).
    (auth/authenticated? request)
    {:status 403 :body "Unauthenticated"}
    ;; In other cases, redirect the user to login page.
    :else
    (let [current-url (:uri request)]
      (response/found (format "?next=%s" current-url)))))

(comment
  (defn login-authenticate
    "Check request username and password against authdata
    username and passwords.
    On successful authentication, set appropriate user
    into the session and redirect to the value of
    (:next (:query-params request)). On failed
    authentication, renders the login page."
    [page request]
    (let [username (get-in request [:form-params "username"])
          password (get-in request [:form-params "password"])
          session (:session request)
          found-password (get authdata (keyword username))]
      (if (and found-password (= found-password password))
        (let [next-url (get-in request [:query-params :next] "/")
              updated-session (assoc session :identity (keyword username))]
          (-> (response/redirect next-url)
              (assoc :session updated-session)))
        page))))

#_(defn wrap-session-auth [handler redirect-url]
    (let [backend (session-backend)]
      (-> handler
          (wrap-authentication backend)
          (wrap-authorization backend))))

#_(defn auth
    "Middleware used in routes that require authentication. If request is not
     authenticated a 401 not authorized response will be returned"
    [handler]
    (fn [request]
      (if (auth/authenticated? request)
        (handler request)
        (response/unauthorized {:error "Not authorized"}))))


