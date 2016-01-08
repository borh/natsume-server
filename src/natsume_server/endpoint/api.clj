(ns natsume-server.endpoint.api
  (:require [bidi.bidi :as bidi]

            [cheshire.core :as json]
            [clojure.core.reducers :as r]
            [clojure.string :as str]

            [d3-compat-tree.tree :refer [D3Tree]]

            [mount.core :refer [defstate]]

            [natsume-server.component.database :refer [connection !norm-map !gram-types] :as db]
            [natsume-server.component.query :as q]
            [natsume-server.config :refer [run-mode config]]

            [natsume-server.nlp.error :as error]
            [natsume-server.nlp.stats :refer [association-measures-graph]]
            [natsume-server.utils.naming :refer [dashes->underscores underscores->dashes]]

            [plumbing.core :refer [for-map map-keys ?>]]
            [schema.core :as s]

            ;;[flatland.ordered.map :as f]
            [natsume-server.endpoint.api-schema-docs :as docs]
            [yada.methods :refer [PostResult GetResult]]
            [yada.yada :refer [yada] :as yada]))

(def ordered-map hash-map) ;; FIXME change when new schema version released

(def opt s/optional-key)
(def req s/required-key)

;; FIXME The following functions need to complete loading in the db ns before we can define the schema below. (Race condition!)
;; (set-norm-map! conn)
;; (set-gram-information! conn)
(s/defschema allowed-types (s/enum :adjective :adverb :auxiliary-verb :fukugoujosi :noun :particle :prefix :preposition :utterance :verb) #_(apply s/enum @db/!gram-types))
(s/defschema allowed-measures (apply s/enum (set (map #_identity (comp keyword dashes->underscores) (conj (keys association-measures-graph) :count)))))
(s/defschema allowed-norms    (apply s/enum [:tokens :chunks :sentences :sources] #_(set (map #_identity (comp keyword dashes->underscores) (keys (delay (deref db/!norm-map)))))))

;; API component

#_(extend-protocol schema.core/Schema
    flatland.ordered.map.OrderedMap
    (spec [this] (s/spec (into {} this)))
    (explain [this] (s/explain (into {} this))))

(def ^:dynamic *pretty-print?* (-> config :http :pretty-print?))

(extend-protocol PostResult
  clojure.lang.PersistentArrayMap
  (interpret-post-result [m ctx]
    (assoc-in ctx [:response :body] (json/generate-string m {:key-fn dashes->underscores :pretty *pretty-print?*}))))

(extend-protocol GetResult
  clojure.lang.LazySeq
  (interpret-get-result [r ctx]
    (assoc-in ctx [:response :body] (json/generate-string r {:key-fn dashes->underscores :pretty *pretty-print?*})))
  clojure.lang.PersistentArrayMap
  (interpret-get-result [m ctx]
    (assoc-in ctx [:response :body] (json/generate-string m {:key-fn dashes->underscores :pretty *pretty-print?*}))))

(defn extract-query-params [ctx]
  (let [q (->> ctx :parameters :query (map-keys underscores->dashes))]
    (if (:measure q)
      (update q :measure (fn [ms] (if (keyword? ms) #{(underscores->dashes ms)} (into #{} (r/map underscores->dashes ms)))))
      q)))

(defn extract-query-type [q]
  (or
   (some->> q
            ((juxt :string-1-pos :string-2-pos :string-3-pos :string-4-pos))
            (take-while identity)
            (map name)
            (str/join "-")
            keyword)
   :noun-particle-verb))

(def !examples (atom {})) ;; FIXME Workaround for inability to attach metadata or new keys to yada Resource
(s/defn get-resource ;; :- yada.resource/Resource
  "GET resource helper"
  [options-map :- {(opt :summary) s/Str
                   (opt :description) s/Str
                   (opt :example-query) {(s/enum :query :body) s/Any #_(s/enum s/Str {s/Keyword s/Any})}
                   :parameters {s/Keyword s/Any}}
   handler-fn :- clojure.lang.IFn]
  (swap! !examples
         assoc (:summary options-map)
         (update (:example-query options-map) :query
                 (fn [m]
                   (merge (zipmap (->> options-map :parameters :query keys
                                       (map (fn [k]
                                              (if (instance? schema.core.OptionalKey k)
                                                (:k k)
                                                k))))
                                  (repeat ""))
                          m))))
  (yada/resource
   {:methods
    {:get
     (-> {:consumes [{:media-type #{"application/x-www-form-urlencoded" "multipart/form-data"} :charset "UTF-8"}]
          :produces {:media-type #_"application/json" #{"application/json" "application/json;pretty=true" #_"application/edn"} :charset "UTF-8"}
          :response handler-fn}
         (merge (select-keys options-map [:summary :description :parameters])))}}))

;; Sources

(defn sources-genre-resource []
  (get-resource
   {:summary "Genre statistics"
    :description "Returns a D3-compatible tree structure of counts (default = sources) by genre"
    :parameters {:query {(opt :norm) allowed-norms}}
    :example-query {:query {:norm "tokens"}}}
   (s/fn :- D3Tree [ctx]
     ((or (->> ctx :parameters :query :norm keyword)
          :sources)
      db/!norm-map))))

(defn sources-genre-similarity-resource []
  (get-resource
   {:summary "Genre similarity statistics"
    :description "[TODO] Return a D3-compatible tree of similarity scores for queried genre"
    :parameters {:query {:genre s/Str}}
    :example-query {:query {:genre "科学技術論文.*"}}}
   (s/fn [ctx]
     (-> ctx :parameters :query :genre
         (clojure.string/split #"\.")))))

;; Sentences

(defn sentences-collocations-resource []
  (get-resource
   {:summary "Collocation_based sentence search"
    :description "Returns sentences matching queried collocation"
    :parameters
    {:query (ordered-map
             (opt :string_1) s/Str
             (opt :string_2) s/Str
             (opt :string_3) s/Str
             (opt :string_4) s/Str
             (opt :string_1_pos) allowed-types
             (opt :string_2_pos) allowed-types
             (opt :string_3_pos) allowed-types
             (opt :string_4_pos) allowed-types
             (opt :genre) s/Str
             (opt :limit) Long
             (opt :offset) Long
             (opt :html) s/Bool
             (opt :sort) s/Str)}
    :example-query {:query {:string_1 "もの" :string_1_pos "noun"
                            :string_2 "が"   :string_2_pos "particle"
                            :string_3 "ある" :string_3_pos "verb"
                            :limit 2}}}
   (s/fn :- {:response
             [(ordered-map
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
               (opt :end-4) s/Int)]}
     [ctx]
     (let [q (extract-query-params ctx)]
       {:response
        (q/query-sentences connection
                            (assoc q :type (extract-query-type q)))}))))

(defn sentences-tokens-resource []
  (get-resource
   {:summary "Token_based sentence search"
    :description "Returns sentences matching queried (SUW) token"
    :parameters
    {:query (ordered-map
             (opt :orth) s/Str
             (opt :orth_base) s/Str
             (opt :pron) s/Str
             (opt :pron_base) s/Str
             (opt :lemma) s/Str
             (opt :pos_1) s/Str
             (opt :pos_2) s/Str
             (opt :pos_3) s/Str
             (opt :pos_4) s/Str
             (opt :c_type) s/Str
             (opt :c_form) s/Str
             (opt :goshu) s/Str                 ;; FIXME
             (opt :genre) s/Str
             (opt :limit) Long
             (opt :offset) Long
             (opt :html) s/Bool
             (opt :sort) s/Str)}
    :example-query {:query {:lemma "花" :limit 3 :html true}}}
   (s/fn :- {:response
             [{(req :text)   s/Str
               (req :genre)  [s/Str]
               (req :title)  s/Str
               (req :author) s/Str
               (req :year)   s/Int}]}
     [ctx]
     {:response
      (q/query-sentences-tokens connection (extract-query-params ctx))})))

;; Tokens

(defn tokens-resource []
  (get-resource
   {:summary "Tokens statistics"
    :description "Returns D3-compatible tree of genre occurrences for queried (SUW) token"
    :parameters
    {:query #_(s/either
               {(req :orth_base) s/Str
                (opt :lemma)     s/Str
                (opt :pos_1)     s/Str
                (opt :pos_2)     s/Str
                (opt :norm)      allowed-norms})
     (ordered-map
      (opt :orth_base) s/Str
      (req :lemma) s/Str
      (opt :pos_1) s/Str
      (opt :pos_2) s/Str
      (opt :norm) allowed-norms)}
    :example-query {:query {:lemma "花" #_:orth_base #_"はな" :norm "sentences"}}}
   (s/fn :- D3Tree [ctx]
     (let [q (extract-query-params ctx)
           {:keys [norm] :or {norm :tokens}} q]
       (q/get-one-search-token connection q :norm (keyword norm))))))

;; Collocations

(defn collocations-resource []
  (get-resource
   {:summary "Collocations statistics"
    :description "Returns collocations of queried (Natsume-specific) units"
    :parameters
    {:query (ordered-map
             (opt :string_1) s/Str
             (opt :string_2) s/Str
             (opt :string_3) s/Str
             (opt :string_4) s/Str
             (opt :string_1_pos) allowed-types
             (opt :string_2_pos) allowed-types
             (opt :string_3_pos) allowed-types
             (opt :string_4_pos) allowed-types
             (opt :measure) allowed-measures
             (opt :limit) Long
             (opt :offset) Long
             (opt :relation_limit) Long
             ;;(opt :scale) s/Bool ;; TODO not implemented
             (opt :compact_numbers) s/Bool)}
    :example-query {:query {:string_1 "花" :string_1_pos "noun"
                            #_:string_2 #_"を" :string_2_pos "particle"
                            :string_3_pos "verb"
                            :limit 3
                            :compact_numbers true}}}
   (s/fn :- {:response
             [(into (ordered-map)
                    (assoc (for-map [measure (->> allowed-measures first second (map underscores->dashes))]
                               (opt measure) s/Num)
                           (opt :string-1) s/Str
                           (opt :string-2) s/Str
                           (opt :string-3) s/Str
                           (opt :string-4) s/Str
                           (opt :data) [(assoc (for-map [measure (->> allowed-measures first second (map underscores->dashes))]
                                                   (opt measure) s/Num)
                                               (opt :string-1) s/Str
                                               (opt :string-2) s/Str
                                               (opt :string-3) s/Str
                                               (opt :string-4) s/Str)]))]}
     [ctx]
     (let [q (->
              {:measure #{:count}
               ;;:aggregates [:count :f-ix :f-xi]
               :offset 0
               :limit 80
               :relation-limit 8
               :compact-numbers true
               :scale false}
              (merge (extract-query-params ctx))
              (update :measure (fn [m] (if (keyword? m) m (first m)))))
           types (extract-query-type q)
           q (if types (assoc q :type types) q)]
       {:response
        (q/query-collocations connection q)}))))

(defn collocations-tree-resource []
  (get-resource
   {:summary "Collocations genre statistics"
    :description "Returns D3-compatible tree of counts matching queried collocation or 1-gram"
    :parameters
    {:query (ordered-map
             (opt :string_1) s/Str
             (opt :string_2) s/Str
             (opt :string_3) s/Str
             (opt :string_4) s/Str
             (opt :string_1_pos) allowed-types
             (opt :string_2_pos) allowed-types
             (opt :string_3_pos) allowed-types
             (opt :string_4_pos) allowed-types
             (opt :genre) s/Str
             (opt :tags) [s/Keyword]
             (opt :measure) [allowed-measures]
             (opt :compact_numbers) s/Bool
             (opt :normalize) s/Bool)}
    :example-query {:query {:string_1 "花"   :string_1_pos "noun"
                            :string_2 "を"   :string_2_pos "particle"
                            :string_3 "切る" :string_3_pos "verb"
                            :measure "chi_sq"
                            :compact_numbers true}}}
   (s/fn :- D3Tree
     [ctx]
     (let [q (merge
              {:compact-numbers true
               :scale true}
              (extract-query-params ctx))
           q (assoc q :type (extract-query-type q))
           n (count (clojure.string/split (name (:type q)) #"-"))]
       (if (or (and (= n 1) (:string-1 q))
               (and (= n 2) (:string-1 q) (:string-2 q))
               (and (= n 3) (:string-1 q) (:string-2 q)
                    (:string-3 q))
               (and (= n 4) (:string-1 q) (:string-2 q)
                    (:string-3 q) (:string-4 q)))
         (q/query-collocations-tree connection q))))))

;; Errors

(defn errors-register-resource []
  (swap! !examples assoc "Text register error analysis" {:body {:body "おちょこちょい書き方がまずいよ！"}})
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
        (error/get-error connection (:body ctx)))}}}))

;; Routing

(defn api []
  ["/api"
   {"/sources"      {"/genre" {""            (yada (sources-genre-resource))
                               ;;"/similarity" (yada (sources-genre-similarity-resource))
                               }}
    "/sentences"    {"/collocations"         (yada (sentences-collocations-resource))
                     "/tokens"               (yada (sentences-tokens-resource))}
    "/tokens"                                (yada (tokens-resource))
    "/collocations" {""                      (yada (collocations-resource))
                     "/tree"                 (yada (collocations-tree-resource))}
    "/errors"       {"/register"             (yada (errors-register-resource))}
    ;;"/suggestions"  {"/tokens"               (yada "TODO")}
    }])

(defn create-routes [routes port server-address]
  [""
   [["/" (docs/index-page routes port !examples server-address)]
    (bidi/routes routes)
    [true (yada/yada nil)]]])

(s/defrecord ApiRoute []
  bidi/RouteProvider
  (routes [_] (api)))

(defn api-route []
  (map->ApiRoute {}))

;; System setup

(defstate api-routes
  :start (when (:server config)
           (create-routes (api-route) (-> config :http :port) (-> config :http :server-address))))
