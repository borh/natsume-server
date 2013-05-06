(ns natsume-server.api.service
  (:require [io.pedestal.service.http :as bootstrap]
            [io.pedestal.service.http.route :as route]
            [io.pedestal.service.http.body-params :as body-params]
            [io.pedestal.service.http.route.definition :refer [defroutes]]
            [io.pedestal.service.interceptor :refer [#_defhandler #_definterceptor defon-response]]

            [ring.util.response :as rr]
            [cheshire.core :as json]

            [me.raynes.fs :as fs]
            [clojure.java.io :as io]

            [natsume-server.config :as cfg]
            [natsume-server.models.db :as db]
            [natsume-server.lm :as lm]))

;; Basic JSON response interceptor using Cheshire.
(defon-response json-interceptor
  [response]
  (-> response
      (update-in [:body] json/generate-string)
      (rr/content-type "application/json;charset=UTF-8")))

(try
  (def allowed-norms (set (keys db/norm-map)))
  (catch Exception e))

(defn view-sources-genre
  "Returns a JSON d3-compatible tree structure of counts by genre.
  Defaults to sources count.
  Checks for valid input, defined as what is available in db/norm-map."
  [request]
  (rr/response ((or (-> request :query-params :norm keyword allowed-norms)
                    :sources)
                db/norm-map)))

(defn view-genre-similarity
  [request]
  (if-let [genre (-> request :query-params :genre (clojure.string/split #"\."))]
    (rr/response (lm/get-genre-similarities genre))))

(defn view-sources-api [request]
  (rr/response "TODO"))

(defn view-tokens [request]
  (let [{:keys [norm] :or {norm :tokens}} (-> request :query-params)] ; TODO :norm should include other measures like tf-idf, dice, etc.
    (rr/response (db/get-search-tokens (:query-params request) {:norm (keyword norm)}))))

#_(defn get-collocations-register [request]
  (if-let [text (-> request :query-params)]))

(comment
  (defn token-pos-name
    ([pos name positive negative]
       {:registerScore (db/register-score pos name positive negative)})
    ([pos name]
       (token-pos-name pos name 2 1))))

(defn list-resources [request] ; For debugging.
  (rr/response {:uri (:uri request)
                :u   (-> request :param :u)
                :resource-path (io/resource (:uri request))
                :request (pr-str request)}))

;; FIXME: find a non-braindead way of doing this... (built-in way)
(defn get-file [request]
  (if-let [file (->> request :path-params :file (str "public/") io/file)]
    (if (fs/file? file)
      (-> file
          slurp
          rr/response
          (rr/content-type "text/html"))
      #_(rr/response (str "invalid file " file)))
    #_(rr/response "no file")))

(defroutes routes
  [[["/api" {:get identity}
     ^:interceptors [(body-params/body-params) json-interceptor] ; All responses under /api should be in JSON format.
     ["/sources" {:get view-sources-api}
      ["/genre" {:get view-sources-genre}
       ["/similarity" {:get view-genre-similarity}]]]
     ["/tokens" {:get view-tokens}]
     #_["/register" TODO {:post get-register-scores :get get-register-scores}]]
    ;; FIXME look into chat client app/templates loading! https://github.com/pedestal/samples/blob/master/chat/chat-client/project.clj
    ["/:file" {:get get-file :post list-resources}
     ^:interceptors [(body-params/body-params) #_bootstrap/html-body #_json-interceptor]]]])

;; You can use this fn or a per-request fn via io.pedestal.service.http.route/url-for
(def url-for (route/url-for-routes routes))

;; Consumed by pedestal-demo.server/create-server
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; :bootstrap/interceptors []
              ::bootstrap/routes routes

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;;::boostrap/allowed-origins ["scheme://host:port"]

              ;; Root for resource interceptor that is available by default.
              ::bootstrap/resource-path "/public"
              ;;::bootstrap/file-path "/"
              ;; Either :jetty or :tomcat (see comments in project.clj
              ;; to enable Tomcat)
              ;;::bootstrap/host "localhost"
              ::bootstrap/type :jetty
              ::bootstrap/port (cfg/opt :server :port)})
