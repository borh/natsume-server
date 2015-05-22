(ns natsume-server.endpoint.api
  (:require [io.pedestal.http.route.definition :refer [defroutes]]
            [io.pedestal.http :as http]
            [natsume-server.endpoint.interceptors :as mw]

            [ring.util.response :refer [response]]
            [pedestal.swagger.core :as swagger]

            [schema.core :as s]
            [plumbing.core :refer [for-map map-keys ?>]]

            [d3-compat-tree.tree :refer [D3Tree]]
            [natsume-server.component.database :as db]
            [natsume-server.nlp.stats :refer [association-measures-graph]]
            [natsume-server.nlp.collocations :refer [extract-collocations]]
            [natsume-server.nlp.error :as error]))

(def opt s/optional-key)
(def req s/required-key)

;; FIXME The following functions need to complete in the db ns before we can define the schema below.
;; (set-norm-map! conn)
;; (set-gram-information! conn)
(s/defschema allowed-types (apply s/enum #{:noun-particle-verb :noun-particle-adjective :adjective-noun} #_@db/!gram-types))
(s/defschema allowed-measures (apply s/enum (set (conj (keys association-measures-graph) :count))))
(s/defschema allowed-norms (apply s/enum (set (keys @db/!norm-map))))

(swagger/defhandler
  view-sources-api
  {:summary    "Returns general summary information on the corpora and documents included in Natsume."
   :parameters {}}
  [request]
  (response "TODO"))

(swagger/defhandler
  view-sources-genre
  {:summary    "Returns a JSON d3-compatible tree structure of counts by genre.
               Defaults to sources count.
               Checks for valid input, defined as what is available in db/norm-map."
   :parameters {:query {(opt :norm) allowed-norms}}
   :responses  {200 {:schema D3Tree}}}
  [{:keys [query-params]}]
  (response ((or (->> query-params :norm keyword)
                 :sources)
              @db/!norm-map)))

(swagger/defhandler
  view-genre-similarity
  {:summary    ""
   :parameters {:query {:genre [s/Str]}}
   :responses  {200 {:schema {}}}}
  [{:keys [query-params]}]
  (if-let [genre (-> query-params :genre (clojure.string/split #"\."))]
    (response :TODO #_(lm/get-genre-similarities genre))))

(swagger/defhandler
  view-tokens
  {:summary    "Natsumeに含まれる単語（短単位、Natsume特有のユニット）の検索が行える。"
   :parameters {:query
                #_(s/either
                  {(req :orth-base) s/Str
                   (opt :lemma)     s/Str
                   (opt :pos-1)     s/Str
                   (opt :pos-2)     s/Str
                   (opt :norm)      allowed-norms})

                {(opt :orth-base) s/Str
                 (req :lemma)     s/Str
                 (opt :pos-1)     s/Str
                 (opt :pos-2)     s/Str
                 (opt :norm)      allowed-norms}}
   :responses  {200 {:schema D3Tree}}}
  [{:keys [conn query-params]}]
  (let [{:keys [genre norm] :or {norm :tokens}} query-params] ; TODO :norm should include other measures like tf-idf, dice, etc.
    ;; FIXME actually, norm should not be settable, but only indirectly available through other measures like tf-idf, etc.
    (if query-params
      (response (db/get-one-search-token conn query-params :norm (keyword norm))))))

(swagger/defhandler
  view-sentences-by-collocation
  {:summary    ""
   :parameters {:query {(opt :string-1) s/Str
                        (opt :string-2) s/Str
                        (opt :string-3) s/Str
                        (opt :string-4) s/Str
                        (opt :genre)    [s/Str]
                        (req :type)     allowed-types
                        (opt :limit)    Long
                        (opt :offset)   Long
                        (opt :html)     s/Bool
                        (opt :sort)     s/Str}}
   :responses  {200 {:schema [{(req :text)    s/Str
                               (req :genre)   [s/Str]
                               (req :title)   s/Str
                               (req :author)  s/Str
                               (req :year)    s/Int
                               (opt :begin-1) s/Int
                               (opt :begin-2) s/Int
                               (opt :begin-3) s/Int
                               (opt :begin-4) s/Int
                               (opt :end-1)   s/Int
                               (opt :end-2)   s/Int
                               (opt :end-3)   s/Int
                               (opt :end-4)   s/Int}]}}}
  [{:keys [conn query-params]}]
  ;; validate: html sort order
  (let [sentences (db/query-sentences conn query-params)]
    (if (not-empty sentences)
      (response sentences))))

(swagger/defhandler
  view-sentences-by-token
  {:summary    ""
   :parameters {:query {(opt :orth-base) s/Str
                        (req :lemma)     s/Str
                        (opt :pos-1)     s/Str
                        (opt :pos-2)     s/Str
                        (opt :genre)    [s/Str]
                        (opt :limit)    Long
                        (opt :offset)   Long
                        (opt :html)     s/Bool
                        (opt :sort)     s/Str}}
   :responses  {200 {:schema [{(req :text)    s/Str
                               (req :genre)   [s/Str]
                               (req :title)   s/Str
                               (req :author)  s/Str
                               (req :year)    s/Int}]}}}
  [{:keys [conn query-params]}]
  ;; validate: html sort order
  (let [sentences (db/query-sentences conn query-params)] ;; FIXME
    (if (not-empty sentences)
      (response sentences))))


(swagger/defhandler
  view-collocations-tree
  {:summary    ""
   :parameters {:query {(opt :string-1) s/Str
                        (opt :string-2) s/Str
                        (opt :string-3) s/Str
                        (opt :string-4) s/Str
                        (opt :genre)    [s/Str]
                        (req :type)     allowed-types
                        (opt :limit)    Long               ;; FIXME these are not used, right???
                        (opt :offset)   Long
                        (opt :html)     s/Bool
                        (opt :sort)     s/Str}}
   :responses  {200 {:schema #_D3Tree {s/Keyword s/Any}}}
   }
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
  {:summary    "Returns positions of errors by error type and confidence.

現状では、エラータイプの指摘範囲をレジスター選択誤りに限定する。"
   ;;:parameters {:body s/Str} ;; FIXME Does not work (middleware problem?). Currently "Content-Type: text/plain" needs to be set for client query to work.
   :responses  {200 {:schema {s/Keyword s/Any}}}}
  [{:keys [conn body query-params] :as request}]
  ;; FIXME update-in all morphemes all positions with value equal to the end position of the last sentence (or 0 for first sentence) .
  (let [body-text (slurp body)]
    (if-let [results (error/get-error conn body-text)]
      (response results))))

;; TODO Any way to standardize this query in, results out step w/ perhaps custom function/macro?
;; FIXME Follow JSON API recommendations: http://jsonapi.org/format/#url-based-json-api as well as d3 use-cases.
;; TODO Add links to sentence view queries etc. (with link-for Pedestal equiv.)
;; FIXME Collocation view: we want genre to be settable -> default to top-level only (we don't need the genre tree?) and never return genre information (except perhaps as a separate field in the response.)
;; FIXME Genre collocation view: use tree structure, but for one or more fully-specified collocations only! Should this be split into another api path?
(swagger/defhandler
  view-collocations
  {:summary    "Natsumeに含まれる共起表現（2―4グラム）の検索が行える。"
   :parameters {:query {(opt :string-1)        s/Str
                        (opt :string-2)        s/Str
                        (opt :string-3)        s/Str
                        (opt :string-4)        s/Str
                        (req :type)            allowed-types
                        (opt :measure)         allowed-measures
                        (opt :limit)           Long
                        (opt :offset)          Long
                        (opt :relation-limit)  Long
                        (opt :compact-numbers) s/Bool
                        (opt :scale)           s/Bool
                        (opt :debug)           s/Bool}}
   :responses  {200 {:schema [{(opt :string-1) s/Str
                               (opt :string-2) s/Str
                               (opt :string-3) s/Str
                               (opt :string-4) s/Str
                               (req :data)     [(assoc      ;; FIXME s/enum not valid as key?
                                                  (for-map [measure allowed-measures]
                                                    (opt measure) s/Num)
                                                  (opt :string-1) s/Str
                                                  (opt :string-2) s/Str
                                                  (opt :string-3) s/Str
                                                  (opt :string-4) s/Str)]}]}}}
  [{:keys [conn query-params]}]
  ;; TODO (log-)scaling from 0-100 for display.
  (let [q (merge {:type :noun-particle-verb
                  :measure :count
                  ;;:aggregates [:count :f-ix :f-xi]
                  :offset 0
                  :limit 80
                  :relation-limit 8
                  :compact-numbers true
                  :scale false
                  :debug false}
                 query-params)]
    (if-let [r (db/query-collocations conn q)]
      (response r))))

(swagger/defroutes
  api-endpoint
  {:title "Natsume Server API"
   :description "Natsume Server API"
   :version "1.0"}
  [[["/api" {:get identity}

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


      ;; on-response ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


      http/json-body
      (swagger/body-params :json-params)
      mw/json-interceptor ;; Response-specific

      ;; after ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

      (swagger/validate-response)]

     ["/sources" {:get view-sources-api}
      ["/genre" {:get view-sources-genre}
       ["/similarity" {:get view-genre-similarity}]]]
     ["/tokens" {:get view-tokens}]
     ["/sentences"
      ["/collocations" {:get view-sentences-by-collocation}]
      ["/tokens" {:get view-sentences-by-token}]]
     ["/collocations" {:get view-collocations}
      ["/tree" {:get view-collocations-tree}]]
     ["/errors"
      ["/register"                                          ;; ^:interceptors [mw/read-body]
       {:post get-text-register}]]]

    ;; Swagger Documentation
    ["/doc" ^:interceptors [mw/custom-decode-params
                            (swagger/coerce-params)
                            (swagger/body-params :json-params)
                            mw/json-interceptor]
     {:get [(swagger/swagger-doc)]}]
    ["/*resource" {:get [(swagger/swagger-ui)]}]]])