(ns natsume-server.endpoint.api
  (:require [aleph.http :as http]
            [bidi.bidi :refer [RouteProvider] :as bidi]
            [bidi.ring :refer [make-handler]]
            [cheshire.core :as json]
            [clojure.core.reducers :as r]
            [clojure.string :as str]
            [com.stuartsierra.component :refer [start system-map Lifecycle system-using using]]
            [d3-compat-tree.tree :refer [D3Tree]]
            [hiccup.page :refer [html5]]
            [natsume-server.component.database :as db]
            [natsume-server.config :as config]
            [natsume-server.nlp.error :as error]
            [natsume-server.nlp.stats :refer [association-measures-graph]]
            [natsume-server.utils.naming :refer [dashes->lower-camel]]
            [plumbing.core :refer [for-map map-keys ?>]]
            [schema.core :as s]

            ;;[flatland.ordered.map :as f]
            [yada.methods :refer [PostResult GetResult]]
            [yada.swagger :refer [swaggered]]
            [yada.yada :refer [yada] :as yada]))

;; Ugly workaround to get connection

(def !connection (atom nil))

(defn client-db-connection []
  (if-not @!connection
    (reset! !connection (db/druid-pool (:db config/defaults)))
    @!connection))

(def ordered-map hash-map) ;; FIXME change when new schema version released

(def opt s/optional-key)
(def req s/required-key)

;; FIXME The following functions need to complete loading in the db ns before we can define the schema below. (Race condition!)
;; (set-norm-map! conn)
;; (set-gram-information! conn)
(s/defschema allowed-types (s/enum :adjective :adverb :auxiliary-verb :fukugoujosi :noun :particle :prefix :preposition :utterance :verb)
  #_(apply s/enum #_#{:noun-particle-verb :noun-particle-adjective :adjective-noun} (map dashes->lower-camel @db/!gram-types)))
(s/defschema allowed-measures (apply s/enum (set (map identity #_dashes->lower-camel (conj (keys association-measures-graph) :count)))))
(s/defschema allowed-norms    (apply s/enum (set (map identity #_dashes->lower-camel (keys @db/!norm-map)))))

;; API component

#_(extend-protocol schema.core/Schema
  flatland.ordered.map.OrderedMap
  (spec [this] (s/spec (into {} this)))
  (explain [this] (s/explain (into {} this))))

(extend-protocol PostResult
  clojure.lang.PersistentArrayMap
  (interpret-post-result [m ctx]
    (assoc-in ctx [:response :body] (json/generate-string m #_{:key-fn dashes->lower-camel}))))

(extend-protocol GetResult
  clojure.lang.LazySeq
  (interpret-get-result [r ctx]
    (assoc-in ctx [:response :body] (json/generate-string r #_{:key-fn dashes->lower-camel})))
  clojure.lang.PersistentArrayMap
  (interpret-get-result [m ctx]
    (assoc-in ctx [:response :body] (json/generate-string m #_{:key-fn dashes->lower-camel}))))

;; curl -i -X POST http://localhost:3006/errors -d "THERE IS DATA HERE" -H "Content-Type: text/plain"

(defn extract-query-type [q]
  (or
   (some->> q
            (juxt :string-1-pos :string-2-pos :string-3-pos :string-4-pos)
            (remove nil?)
            (map name)
            (str/join "-")
            keyword)
   :noun-particle-verb))

(s/defn get-resource ;; :- yada.resource/Resource
  [summary :- s/Str
   description :- s/Str
   params :- {s/Keyword s/Any}
   fn :- clojure.lang.IFn]
  (yada/resource
   {:methods
    {:get
     {:consumes [{:media-type #{"application/x-www-form-urlencoded" "multipart/form-data"} :charset "UTF-8"}]
      :produces {:media-type "application/json" #_#{"application/json" "application/json;pretty=true" #_"application/edn"} :charset "UTF-8"}
      ;;:responses {404 {:description "Resource not found"}}
      :summary summary
      :description description
      :parameters params
      :response fn}}}))

;; Sources

(defn sources-genre-resource []
  (get-resource
   "Genre statistics"
   "Returns a D3-compatible tree structure of counts (default = sources) by genre"
   {:query {(opt :norm) allowed-norms}}
   (s/fn :- D3Tree [ctx]
     ((or (->> ctx :parameters :query :norm keyword)
          :sources)
      @db/!norm-map))))

(defn sources-genre-similarity-resource []
  (get-resource
   "Genre similarity statistics"
   "[TODO] Return a D3-compatible tree of similarity scores for queried genre"
   {:query {:genre s/Str}}
   (s/fn [ctx]
     (-> ctx :parameters :query :genre
         (clojure.string/split #"\.")))))

;; Sentences

(defn sentences-collocations-resource []
  (get-resource
   "Collocation-based sentence search"
   "Returns sentences matching queried collocation"
   {:query (ordered-map
            (opt :string-1) s/Str
            (opt :string-2) s/Str
            (opt :string-3) s/Str
            (opt :string-4) s/Str
            (opt :string-1-pos) allowed-types
            (opt :string-2-pos) allowed-types
            (opt :string-3-pos) allowed-types
            (opt :string-4-pos) allowed-types
            (opt :genre) s/Str
            (opt :limit) Long
            (opt :offset) Long
            (opt :html) s/Bool
            (opt :sort) s/Str)}
   (s/fn :- [(ordered-map
              (req :text) s/Str
              (req :genre) [s/Str]
              (req :title) s/Str
              (req :author) s/Str
              (req :year) s/Int
              (opt :begin-1) s/Int
              (opt :end-1) s/Int
              (opt :begin-2) s/Int
              (opt :end-2) s/Int
              (opt :begin-3) s/Int
              (opt :end-3) s/Int
              (opt :begin-4) s/Int
              (opt :end-4) s/Int)]
     [ctx]
     (let [q (-> ctx :parameters :query)]
       (db/query-sentences (client-db-connection)
                           (assoc q :type (extract-query-type q)))))))

(defn sentences-tokens-resource []
  (get-resource
   "Token-based sentence search"
   "Returns sentences matching queried (SUW) token"
   {:query (ordered-map
            (opt :orth) s/Str
            (opt :orth-base) s/Str
            (opt :pron) s/Str
            (opt :pron-base) s/Str
            (opt :lemma) s/Str
            (opt :pos-1) s/Str
            (opt :pos-2) s/Str
            (opt :pos-3) s/Str
            (opt :pos-4) s/Str
            (opt :c-type) s/Str
            (opt :c-form) s/Str
            (opt :goshu) s/Str                 ;; FIXME
            (opt :genre) s/Str
            (opt :limit) Long
            (opt :offset) Long
            (opt :html) s/Bool
            (opt :sort) s/Str)}
   (s/fn :- [{(req :text)   s/Str
              (req :genre)  [s/Str]
              (req :title)  s/Str
              (req :author) s/Str
              (req :year)   s/Int}]
     [ctx]
     (db/query-sentences-tokens (client-db-connection)
                                (-> ctx :parameters :query)))))

;; Tokens

(defn tokens-resource []
  (get-resource
   "Tokens statistics"
   "Returns D3-compatible tree of genre occurrences for queried (SUW) token"
   {:query #_(s/either
              {(req :orth-base) s/Str
               (opt :lemma)     s/Str
               (opt :pos-1)     s/Str
               (opt :pos-2)     s/Str
               (opt :norm)      allowed-norms})
    (ordered-map
     (opt :orth-base) s/Str
     (req :lemma) s/Str
     (opt :pos-1) s/Str
     (opt :pos-2) s/Str
     (opt :norm) allowed-norms)}
   (s/fn :- D3Tree [ctx]
     (let [q (-> ctx :parameters :query)
           {:keys [norm] :or {norm :tokens}} q]
       (db/get-one-search-token (client-db-connection) q :norm (keyword norm))))))

;; Collocations

(defn collocations-resource []
  (get-resource
   "Collocations statistics"
   "Returns collocations of queried (Natsume-specific) units"
   {:query (ordered-map
            (opt :string-1) s/Str
            (opt :string-2) s/Str
            (opt :string-3) s/Str
            (opt :string-4) s/Str
            (opt :string-1-pos) allowed-types
            (opt :string-2-pos) allowed-types
            (opt :string-3-pos) allowed-types
            (opt :string-4-pos) allowed-types
            (opt :measure) allowed-measures
            (opt :limit) Long
            (opt :offset) Long
            (opt :relation-limit) Long
            ;;(opt :scale) s/Bool ;; TODO not implemented
            (opt :compact-numbers) s/Bool)}
   (s/fn :- [(into (ordered-map)
                   (assoc (for-map [measure (-> allowed-measures first second)]
                              (opt measure) s/Num)
                          (opt :string-1) s/Str
                          (opt :string-2) s/Str
                          (opt :string-3) s/Str
                          (opt :string-4) s/Str
                          (opt :data) [(assoc (for-map [measure (-> allowed-measures first second)]
                                                  (opt measure) s/Num)
                                              (opt :string-1) s/Str
                                              (opt :string-2) s/Str
                                              (opt :string-3) s/Str
                                              (opt :string-4) s/Str)]))]
     [ctx]
     (let [q (merge
              {:measure :count
               ;;:aggregates [:count :f-ix :f-xi]
               :offset 0
               :limit 80
               :relation-limit 8
               :compact-numbers true
               :scale false}
              (-> ctx :parameters :query))
           types (extract-query-type q)
           q (if types (assoc q :type types) q)]
       (db/query-collocations (client-db-connection) q)))))

(defn collocations-tree-resource []
  (get-resource
   "Collocations genre statistics"
   "Returns D3-compatible tree of counts matching queried collocation or 1-gram"
   {:query (ordered-map
            (opt :string-1) s/Str
            (opt :string-2) s/Str
            (opt :string-3) s/Str
            (opt :string-4) s/Str
            (opt :string-1-pos) allowed-types
            (opt :string-2-pos) allowed-types
            (opt :string-3-pos) allowed-types
            (opt :string-4-pos) allowed-types
            (opt :genre) s/Str
            (opt :tags) [s/Keyword]
            (opt :measure) allowed-measures
            (opt :compact-numbers) s/Bool
            (opt :normalize) s/Bool)}
   (s/fn :- D3Tree
     [ctx]
     (let [q (merge
              {:compact-numbers true
               :scale true}
              (-> ctx :parameters :query))
           q (assoc q :type (extract-query-type q))
           n (count (clojure.string/split (name (:type q)) #"-"))]
       (if (or (and (= n 1) (:string-1 q))
               (and (= n 2) (:string-1 q) (:string-2 q))
               (and (= n 3) (:string-1 q) (:string-2 q)
                    (:string-3 q))
               (and (= n 4) (:string-1 q) (:string-2 q)
                    (:string-3 q) (:string-4 q)))
         (db/query-collocations-tree (client-db-connection) q))))))

;; Errors

(defn errors-register-resource []
  (yada/resource
   {:methods
    {:post
     {:summary "Text register error analysis"
      :description "Returns positions of register-related errors by error type (tokens or grams) and confidence"
      :consumes [{:media-type #{"text/plain"} :charset "UTF-8"}]
      :produces [{:media-type #{"application/json" "application/edn"} :charset "UTF-8"}]
      :parameters {:body s/Str}
      :response
      (s/fn :- {s/Keyword s/Any}
        [ctx]
        (error/get-error (client-db-connection) (:body ctx)))}}}))

;; Routing

(def swagger-info
  {:info
   {:title       "Natsume Server API"
    :description "Documentation for the Natsume Server API"
    :version     "1.0"
    :contact     {:name  "Bor Hodošček"
                  :email "hinoki-project@googlegroups.com"
                  :url   "https://hinoki-project.org"}
    :license     {:name "Eclipse Public License"
                  :url  "http://www.eclipse.org/legal/epl-v10.html"}}
   :basePath "/api"})

(defn api []
  ["/api"
   {"/sources"      {"/genre" {""            (yada (sources-genre-resource))
                               "/similarity" (yada (sources-genre-similarity-resource))}}
    "/sentences"    {"/collocations"         (yada (sentences-collocations-resource))
                     "/tokens"               (yada (sentences-tokens-resource))}
    "/tokens"                                (yada (tokens-resource))
    "/collocations" {""                      (yada (collocations-resource))
                     "/tree"                 (yada (collocations-tree-resource))}
    "/errors"       {"/register"             (yada (errors-register-resource))}
    "/suggestions"  {"/tokens"               (yada "TODO")}}])

(s/defrecord ApiComponent []
  RouteProvider
  (routes [_] (api)))

(defn new-api-component []
  (map->ApiComponent {}))

;; System setup

(defn describe-routes
  "An example of the kind of thing you can do when your routes are data"
  [api]
  (for [{:keys [path handler]} (bidi/route-seq (bidi/routes api))]
    {:path (apply str path)
     :description (get-in handler [:properties :doc/description])
     :handler handler}))

(defprotocol ISchemaPrint
  (schema-print [s]))

(extend-protocol ISchemaPrint

  schema.core.OptionalKey
  (schema-print [s]
    (str (-> s vals first schema-print)))

  schema.core.EnumSchema
  (schema-print [s]
    (let [es (-> s first second sort)
          es (if (> (count es) 10) (concat (take 10 es) '("...")) es)]
      (->> es
           (map schema-print)
           (str/join "' | '")
           (format "Enum( '%s' )"))))

  schema.core.Predicate
  (schema-print [s]
    (case (some-> s :pred-name str)
      "keyword?" "String"))

  ;;clojure.lang.MapEntry
  ;;(schema-print [s] [(schema-print (key s))
  ;;                   (schema-print (val s))])

  clojure.lang.Keyword
  (schema-print [s] (name s #_(dashes->lower-camel s)))

  java.lang.String
  (schema-print [s] s)

  ;;java.util.regex.Pattern
  ;;(schema-print [s] (schema-print (str s)))

  clojure.lang.PersistentVector
  (schema-print [s] (do #_(println s) #_(println (mapv schema-print (sort s))) (format "Vec( %s )" (str/join ", " (mapv schema-print (sort s))))))

  clojure.lang.PersistentHashSet
  (schema-print [s] (str (schema-print (class (first s))) " (" (str/join " | " (map schema-print s)) ")"))

  ;;clojure.lang.PersistentHashMap
  ;;(schema-print [s] (mapv schema-print s))

  java.lang.Class
  (schema-print [s] (schema-print (last (str/split (.getName s) #"\."))))

  nil
  (schema-print [s] "nil"))

(schema-print [s/Str])

(defn index-page [api port]
  (yada/yada
   (merge
    (yada/as-resource
     (html5 {:encoding "UTF-8"}
      [:link {:type "text/css" :href "http://yui.yahooapis.com/pure/0.6.0/pure-min.css" :rel "stylesheet"}]
      [:div.pure-g
       [:div.pure-u-1-24]
       [:div.pure-u-22-24
        [:h1 "natsume-server API Documentation"]
        (for [{:keys [path description handler]} (describe-routes api)]
          [:div
           [:h2 path]
           [:ul
            (for [method (clojure.set/intersection #{:get :post} (:allowed-methods handler))
                  :let [meth (str/upper-case (str (name method)))
                        param-type (case method :get :query :post :body :head nil :options nil)
                        {:keys [description summary consumes produces]} (-> handler :resource :methods method)]]
              [:li
               [:h3 summary]
               [:p [:b "Description: "] description]
               [:p [:b "Method: "] meth]
               [:pre (format "curl -i -X %s http://localhost:%d%s" meth port path)]
               [:p [:b "Input media types: "] (schema-print (apply s/enum (mapv :name (map :media-type consumes))))]
               [:p [:b "Output media types: "] (schema-print (apply s/enum (mapv :name (map :media-type produces))))]
               (if-let [ps (some-> handler :resource :methods method :parameters)]
                 (case method
                   :get
                   [:div
                    [:h4 "Parameters"]
                    [:table.pure-table
                     [:thead [:td [:b "Field"]] [:td [:b "Value schema"]]]
                     [:tbody
                      (for [[k v] (into (sorted-map-by (fn [a b] (compare (schema-print a) (schema-print b)))) (param-type ps))]
                        [:tr [:td (schema-print k)] [:td (schema-print v)]])]]]
                   :post
                   [:div
                    [:h4 "Parameters"]
                    [:table.pure-table
                     [:thead
                      [:td [:b "Body"]]
                      [:td (schema-print (param-type ps))]]]]))])]])]]))
    {:produces {:media-type "text/html" :charset "UTF-8"}})))

(defn create-routes [api port]
  [""
   [["/" (index-page api port)]
    (bidi/routes api)
    [true (yada/yada nil)]]])

(defrecord ServerComponent [api port]
  Lifecycle
  (start [component]
    (let [routes (create-routes api port)]
      (assoc component
             :routes routes
             :server (http/start-server (make-handler routes) {:port port :raw-stream? true}))))
  (stop [component]
    (when-let [server (:server component)]
      (.close server))
    (dissoc component :server)))

(defn new-server-component [config]
  (map->ServerComponent config))

(defn new-system-map [config]
  (system-map
   :api (new-api-component)
   :server (new-server-component config)))

(defn new-dependency-map []
  {:api {}
   :server [:api]})

(defn new-co-dependency-map []
  {:api {:server :server}})

(s/defschema UserPort (s/both s/Int (s/pred #(<= 1024 % 65535))))

(s/defn new-api-app [config :- {:port UserPort}]
  (-> (new-system-map config)
      (system-using (new-dependency-map))))
