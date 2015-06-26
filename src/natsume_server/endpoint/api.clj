(ns natsume-server.endpoint.api
  (:require [io.pedestal.http.route.definition :refer [defroutes]]
            [io.pedestal.http :as http]
            [natsume-server.endpoint.interceptors :as mw]

            [ring.util.response :refer [response]]
            [pedestal.swagger.core :as swagger]
            ;;[flatland.ordered.map :refer [ordered-map]] ;; FIXME change when new schema version released

            [schema.core :as s]
            [plumbing.core :refer [for-map map-keys ?>]]

            [d3-compat-tree.tree :refer [D3Tree]]
            [natsume-server.component.database :as db]
            [natsume-server.nlp.stats :refer [association-measures-graph]]
            [natsume-server.nlp.collocations :refer [extract-collocations]]
            [natsume-server.nlp.error :as error]))

(def ordered-map hash-map) ;; FIXME change when new schema version released

(def opt s/optional-key)
(def req s/required-key)

;; FIXME The following functions need to complete loading in the db ns before we can define the schema below. (Race condition!)
;; (set-norm-map! conn)
;; (set-gram-information! conn)
(s/defschema allowed-types    (apply s/enum #{:noun-particle-verb :noun-particle-adjective :adjective-noun} #_@db/!gram-types))
(s/defschema allowed-measures (apply s/enum (set (conj (keys association-measures-graph) :count))))
(s/defschema allowed-norms    (apply s/enum (set (keys @db/!norm-map))))

(swagger/defhandler
  view-sources-api
  {:summary    "Returns general summary information on the corpora and documents included in Natsume"
   :parameters {}}
  [request]
  (response "TODO"))

(swagger/defhandler
  view-sources-genre
  {:summary    "Returns a D3-compatible tree structure of counts (default = sources) by genre"
   :parameters {:query {(opt :norm) allowed-norms}} ;; FIXME default parameters with swagger?
   :responses  {200 {:schema s/Any #_D3Tree}}}
  [{:keys [query-params]}]
  (response ((or (->> query-params :norm keyword)
                 :sources)
              @db/!norm-map)))

(swagger/defhandler
  view-genre-similarity
  {:summary    "Return a D3-compatible tree of similarity scores for queried genre"
   :parameters {:query {:genre [s/Str]}}
   :responses  {200 {:schema {}}}}
  [{:keys [query-params]}]
  (if-let [genre (-> query-params :genre first (clojure.string/split #"\."))]
    (response :TODO #_(lm/get-genre-similarities genre))))

(swagger/defhandler
  view-tokens
  {:summary    "Returns D3-compatible tree of genre occurrences for queried (SUW) token"
   :parameters {:query
                #_(s/either
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
   :responses  {200 {:schema D3Tree}}}
  [{:keys [conn query-params]}]
  (let [{:keys [genre norm] :or {norm :tokens}} query-params] ; TODO :norm should include other measures like tf-idf, dice, etc.
    ;; FIXME actually, norm should not be settable, but only indirectly available through other measures like tf-idf, etc.
    (if query-params
      (response (db/get-one-search-token conn query-params :norm (keyword norm))))))

(swagger/defhandler
  view-sentences-by-collocation
  {:summary    "Returns sentences matching queried collocation"
   :parameters {:query (ordered-map
                         (opt :string-1) s/Str
                         (opt :string-2) s/Str
                         (opt :string-3) s/Str
                         (opt :string-4) s/Str
                         (opt :genre) [s/Str]
                         (req :type) allowed-types
                         (opt :limit) Long
                         (opt :offset) Long
                         (opt :html) s/Bool
                         (opt :sort) s/Str)}
   :responses  {200 {:schema [(ordered-map
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
                                (opt :end-4) s/Int)]}}}
  [{:keys [conn query-params]}]
  ;; FIXME validate: sort order; n <=> string-{1,2,3,4} sanity check
  (println query-params)
  (let [sentences (db/query-sentences conn query-params)]
    (if (not-empty sentences)
      (response sentences))))

(swagger/defhandler
  view-sentences-by-token
  {:summary    "Returns sentences matching queried (SUW) token"
   :parameters {:query (ordered-map
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
                         (opt :genre) [s/Str]
                         (opt :limit) Long
                         (opt :offset) Long
                         (opt :html) s/Bool
                         (opt :sort) s/Str)}
   :responses  {200 {:schema [{(req :text)   s/Str
                               (req :genre)  [s/Str]
                               (req :title)  s/Str
                               (req :author) s/Str
                               (req :year)   s/Int}]}}}
  [{:keys [conn query-params]}]
  ;; validate: html sort order
  (let [sentences (db/query-sentences-tokens conn query-params)] ;; FIXME
    (if (not-empty sentences)
      (response sentences))))


(swagger/defhandler
  view-collocations-tree
  {:summary    "Returns D3-compatible tree of counts matching queried collocation or 1-gram"
   :parameters {:query (ordered-map
                         (opt :string-1) s/Str
                         (opt :string-2) s/Str
                         (opt :string-3) s/Str
                         (opt :string-4) s/Str
                         (opt :genre) [s/Str]
                         (req :type) allowed-types
                         (opt :limit) Long                  ;; FIXME these are not used, right???
                         (opt :offset) Long
                         (opt :html) s/Bool
                         (opt :sort) s/Str)}
   :responses  {200 {:schema #_D3Tree {s/Keyword s/Any}}}}
  [{:keys [conn query-params]}]
  (let [query-params (assoc query-params :compact-numbers true :scale true)
        n (count (clojure.string/split (name (:type query-params)) #"-"))]
    ;; FIXME quick-and-dirty validation
    (if (or (and (= n 1) (:string-1 query-params))
            (and (= n 2) (:string-1 query-params) (:string-2 query-params))
            (and (= n 3) (:string-1 query-params) (:string-2 query-params)
                 (:string-3 query-params))
            (and (= n 4) (:string-1 query-params) (:string-2 query-params)
                 (:string-3 query-params) (:string-4 query-params)))
      (if-let [r (db/query-collocations-tree conn query-params)]
        (response r)))))

(swagger/defhandler
  get-text-register
  {:summary    "Returns positions of errors by error type and confidence
現状では、エラータイプの指摘範囲をレジスター選択誤りに限定する。"
   :parameters {:body s/Any #_{:text s/Str}} ;; FIXME Does not work (middleware problem?). Currently "Content-Type: text/plain" needs to be set for client query to work.
   :responses  {200 {:schema {s/Keyword s/Any}}}}
  [{:keys [conn body-params body] :as request}]
  ;; FIXME update-in all morphemes all positions with value equal to the end position of the last sentence (or 0 for first sentence).
  (let [body-text (slurp body) #_(:text body-params)]
    (if-let [results (error/get-error conn body-text)]
      (response results))))

;; TODO Any way to standardize this query in, results out step w/ perhaps custom function/macro?
;; FIXME Follow JSON API recommendations: http://jsonapi.org/format/#url-based-json-api as well as d3 use-cases.
;; TODO Add links to sentence view queries etc. (with link-for Pedestal equiv.)
;; FIXME Collocation view: we want genre to be settable -> default to top-level only (we don't need the genre tree?) and never return genre information (except perhaps as a separate field in the response.)
;; FIXME Genre collocation view: use tree structure, but for one or more fully-specified collocations only! Should this be split into another api path?
(swagger/defhandler
  view-collocations
  {:summary    "Returns collocations of queried (Natsume-specific) units"
   :parameters {:query (ordered-map
                         (opt :string-1) s/Str
                         (opt :string-2) s/Str
                         (opt :string-3) s/Str
                         (opt :string-4) s/Str
                         (req :type) allowed-types
                         (opt :measure) allowed-measures
                         (opt :limit) Long
                         (opt :offset) Long
                         (opt :relation-limit) Long
                         ;;(opt :scale) s/Bool ;; TODO not implemented
                         (opt :compact-numbers) s/Bool)}
   :responses  {200 {:schema [(ordered-map (opt :string-1) s/Str
                                           (opt :string-2) s/Str
                                           (opt :string-3) s/Str
                                           (opt :string-4) s/Str
                                           (req :data) [{s/Keyword s/Any} #_(assoc      ;; FIXME s/enum not valid as key?
                                                  (for-map [measure allowed-measures]
                                                    (opt measure) s/Num)
                                                  (opt :string-1) s/Str
                                                  (opt :string-2) s/Str
                                                  (opt :string-3) s/Str
                                                  (opt :string-4) s/Str)])]}}}
  [{:keys [conn query-params]}]
  ;; TODO (log-)scaling from 0-100 for display.
  (let [q (merge {:type :noun-particle-verb
                  :measure :count
                  ;;:aggregates [:count :f-ix :f-xi]
                  :offset 0
                  :limit 80
                  :relation-limit 8
                  :compact-numbers true
                  :scale false}
                 query-params)]
    (if-let [r (db/query-collocations conn q)]
      (response r))))

(defn get-suggestions-tokens [])

(swagger/defroutes
  api-endpoint
  {:info {:title       "Natsume Server API"
          :description "Documentation for the Natsume Server API"
          :version     "1.0"
          :contact     {:name  "Bor Hodošček"
                        :email "hinoki-project@googlegroups.com"
                        :url   "https://hinoki-project.org"}
          :license     {:name "Eclipse Public License"
                        :url  "http://www.eclipse.org/legal/epl-v10.html"}}
   :tags [{:name        "Sources"
           :description "methods returning corpus information"}
          {:name        "Sentences"
           :description "methods returning sentences"}
          {:name        "Tokens"
           :description "methods returning tokens"}
          {:name        "Collocations"
           :description "methods returning collocations"}
          {:name        "Errors"
           :description "methods for error-checking text"}
          {:name        "Suggestions"
           :description "methods for token/collocation suggestions"}]}
  [[["/api" {:get identity} ;; FIXME (redirect to root?)

     ^:interceptors
     [;; before ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

      mw/insert-db
      mw/utf8-default
      mw/custom-decode-params
      (swagger/coerce-params)

      ;; on-request ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

      mw/custom-body-params
      mw/kebab-case-params
      (swagger/keywordize-params :form-params :headers)
      (swagger/body-params)

      ;; on-response ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

      ;;http/json-body
      mw/json-interceptor

      ;; after ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

      (swagger/validate-response)]

     ["/sources" ^:interceptors [(swagger/tag-route "Sources")] {:get view-sources-api} ;; TODO
      ["/genre" {:get view-sources-genre}
       ["/similarity" {:get view-genre-similarity}]]]
     ["/sentences" ^:interceptors [(swagger/tag-route "Sentences")]
      ["/collocations" {:get view-sentences-by-collocation}]
      ["/tokens" {:get view-sentences-by-token}]] ;; TODO
     ["/tokens" ^:interceptors [(swagger/tag-route "Tokens")] {:get view-tokens}]
     ["/collocations" ^:interceptors [(swagger/tag-route "Collocations")] {:get view-collocations}
      ["/tree" {:get view-collocations-tree}]]
     ["/errors" ^:interceptors [(swagger/tag-route "Errors")]
      ["/register"                                          ;; ^:interceptors [mw/read-body]
       {:post get-text-register}]]
     ["/suggestions" ^:interceptors [(swagger/tag-route "Suggestions")] ;; TODO Suggest correct orthography given token/collocation.
      ;; TODO Look into how we integrate suggestions with the error API--specifically, how to deal with information on before-after differences (c.f. kosodo data).
      ["/tokens" {:get get-suggestions-tokens}]]]

    ;; Swagger Documentation
    ["/doc" ^:interceptors [mw/custom-decode-params
                            (swagger/coerce-params)
                            (swagger/body-params :json-params)
                            mw/json-interceptor]
     {:get [(swagger/swagger-doc)]}]
    ["/*resource" {:get [(swagger/swagger-ui)]}]]])
