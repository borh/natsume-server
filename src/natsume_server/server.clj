(ns natsume-server.server
  (:require [natsume-server.database :as db]
            [natsume-server.readability :as rd]
            [natsume-server.text :refer [string->sentences string->paragraphs paragraph->sentences]]
            [natsume-server.annotation-middleware :refer [sentence->tree]]
            [cheshire.core :as json]

            [ring.util.response :as ring-response]
            [ring.util.codec :refer [url-decode]]
            [ring.util.response :refer [content-type]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [ring.middleware.cors :refer [wrap-cors]]

            [compojure.core :refer [GET POST context defroutes]]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [compojure.response :refer [Renderable] :as response]

            [taoensso.timbre :as log]
            [natsume-server.log-config :as lc])
  (:gen-class))

;; TODO https://github.com/aphyr/riemann-clojure-client integration

;; # Log configuration
;;
;; We prefer a separate log file 'server.log' to the main 'natsume.log' one.
(lc/setup-log log/config :info)

;; # Natsume logic
#_(defn ->tree
  [paragraphs]
  (map #(map sentence->tree %) paragraphs))

(defn annotate-tokens-in-tree
  "Applies function to all tokens in tree."
  [tree f]
  (map (fn [chunk] (update-in chunk [:tokens] (fn [tokens] (map f tokens)))) tree))

(defn annotate-in-text
  "Applies function to all sentences in text."
  [text f]
  (map (fn [paragraph] (map f paragraph)) text))

(defn error-annotation
  "Annotates tree with register_score given positive and negative input."
  [tree positive negative]
  (annotate-tokens-in-tree
   tree
   #(assoc % :registerScore (db/register-score (str (:pos1 %) (:pos2 %)) (:orthBase %) positive negative))))

(defn filter-token-keys
  [text keys]
  (annotate-in-text text (fn [tree] (annotate-tokens-in-tree tree #(select-keys % keys)))))

(defn paragraph->tree
  [p]
  (->> p
       string->paragraphs
       (map sentence->tree)))

(defn text->tree
  [s]
  (->> s
       string->sentences
       (map (fn [sentence] (map sentence->tree sentence)))))

;; TODO look at preconditions and options maps: {:pre [name]} // http://blog.jayfields.com/2010/07/clojure-destructuring.html

(defn text->tree+error
  ([s]
     (text->tree+error s 1 2)) ; TODO smarter defaults (i.e. by corpus name)
  ([s positive negative]
     (-> s
         text->tree
         (annotate-in-text #(error-annotation % positive negative)))))

(defn tree->token-seq
  [tree]
  (flatten (map :tokens (flatten tree))))

(defn text->tree+error+token+filter
  ([s filter-keys]
     (tree->token-seq (filter-token-keys (text->tree+error s) filter-keys)))
  ([s filter-keys positive negative]
     (tree->token-seq (filter-token-keys (text->tree+error s positive negative) filter-keys))))

;; # Resources
(defn analyze-text
  [body]
  (text->tree+error body))

(defn corpus-genres
  []
  (db/get-genres))

(defn text-analyze-error
  [text filter positive negative]
  (cond
   (and text filter positive negative) (text->tree+error+token+filter text filter positive negative)
   filter                              (text->tree+error+token+filter text filter)
   (and positive negative)             (text->tree+error text positive negative)
   :else                               (text->tree+error text)))

(defn token-pos-name
  ([pos name positive negative]
     {:registerScore (db/register-score pos name positive negative)})
  ([pos name]
     (token-pos-name pos name 2 1)))

;; # Render override for vectors
(extend-protocol Renderable
  clojure.lang.APersistentVector
  (render [resp-map _]
    (ring-response/response resp-map)))

;; # Routes

(defroutes main-routes*
  (context "/corpus" _
           (GET "/genres" _ (corpus-genres))
           (GET "/genres/:name" [name] [name])
           (GET "/genres/:name/npv/:noun/:p/:verb" [name noun p verb] [name noun p verb]))
  (GET "/token/*/:pos" [name] #_(token-pos-name name) "Unimplemented.")
  (GET "/token/error"  [pos orthBase positive negative] (token-pos-name pos orthBase positive negative))
  (GET "/sentence/analyze"  [text] (sentence->tree text))
  (GET "/paragraph/analyze" [text] (paragraph->tree text))
  (GET "/text/analyze"      [text filter positive negative] (text-analyze-error text filter positive negative))
  (GET "/text/split"        [text] (string->sentences text))
  (GET "/paragraph/split"   [text] (paragraph->sentences text))
  (route/not-found "<h1>Route not found.</h1><p>Refer to API documentation: https://github.com/borh/natsume-server/wiki/API-ja</p>"))

;; # Handler with middleware
(defn wrap-request-logger
  [handler]
  (fn [request]
    (log/info request)
    (handler request)))

(defn wrap-json-response
  "Middleware that converts responses with a map for a body into a JSON
response."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (or (coll? (:body response)))
        (-> response
            (content-type "application/json; charset=UTF-8")
            (update-in [:body] json/encode))
        response))))

;; Adapted from https://github.com/ring-clojure/ring/blob/master/ring-core/src/ring/middleware/keyword_params.clj
(defn- decode-key
  "Decodes charcter strings to keywords and integer strings to integers."
  [k]
  (cond (and (string? k) (re-matches #"[A-Za-z*+!_?-][A-Za-z0-9*+!_?-]*" k)) (keyword k)
        (and (string? k) (re-matches #"^\d+$" k)) (Integer/decode k)
        :else k))

(defn- keyify-params [target]
  "Keyifies and decodes params, particulary form-params or query-params that do not have type
   information attached to them. Reference/based on: ring.middleware.keyword-params"
  (cond
   (map? target) (into {}
                       (for [[k v] target]
                         [(decode-key k) (keyify-params v)]))
   (vector? target) (vec (map keyify-params target))
   :else (decode-key target)))

(defn wrap-normalize-json-request
  "Looks in body and query-params for JSON data and merges it with :params.
   Body data has higher priorty and will override any query params, which will override exsisting
   params."
  [handler]
  (fn [request]
    (let [json-body  (try (json/decode (slurp (:body request)) true) (catch Exception e nil))
          json-query (try (json/decode (url-decode (:query-string request)) true) (catch Exception e nil))]
      (handler (-> request
                   (update-in [:params] keyify-params)
                   (update-in [:params] #(merge % json-query json-body)))))))

(def handler
  (-> main-routes*
      wrap-request-logger
      (wrap-cors
       :access-control-allow-origin #"http://localhost:8000" ; FIXME why is "*" not working with Chrome?
       :access-control-allow-headers "Origin, X-Requested-With, Content-Type, Accept"
       :access-control-allow-methods [:get :put])
      wrap-json-response
      wrap-normalize-json-request
      handler/api
      wrap-stacktrace))

;; # Jetty server and main function

(def jetty-atom (atom nil))

(defn stop!
  []
  (swap! jetty-atom #(try (.stop %) (catch Exception e %))))

(defn start! [options]
  (if (not (nil? @jetty-atom)) (stop!))
  (reset! jetty-atom (run-jetty
                      #'handler
                      (assoc options
                        :join? false))))

(defn -main
  ([port]
     (start! {:port (Integer/parseInt port)}))
  ([]
     (-main "5011")))
