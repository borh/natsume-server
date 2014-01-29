(ns natsume-server.api.service
  (:require [io.pedestal.service.http :as bootstrap]
            [io.pedestal.service.http.route :as route]
            [io.pedestal.service.http.body-params :as body-params]
            [io.pedestal.service.http.route.definition :refer [defroutes]]
            [io.pedestal.service.interceptor :refer [defhandler #_definterceptor defon-response defon-request]]

            [ring.util.response :as rr]
            [cheshire.core :as json]

            [me.raynes.fs :as fs]
            [clojure.java.io :as io]
            [clojure.core.reducers :as r]
            [plumbing.core :refer [?> map-keys for-map]]
            [validateur.validation :as v]

            [taoensso.timbre :as timbre]

            [natsume-server.utils.naming :refer [dashes->lower-camel underscores->dashes]]
            [natsume-server.config :as cfg]
            [natsume-server.models.db :as db]
            [natsume-server.stats :refer [association-measures-graph] :as stats]
            [natsume-server.text :as text]
            [natsume-server.annotation-middleware :as anno]
            [natsume-server.lm :as lm]))

;; (timbre/set-config! [:appenders :spit :enabled?] true)
;; (timbre/set-config! [:shared-appender-config :spit-filename] "log/service.log")
;; (timbre/refer-timbre)

;; TODO Optionally do JSON-RPC 2.0

;; Basic JSON response interceptor using Cheshire.
;; FIXME: investigate poor performance at this (Pedestal) layer.
;; TODO: this interceptor might be obviated in next release.
(defon-response json-interceptor
  [response]
  (-> response
      #_(bootstrap/json-response)
      (update-in [:body] json/generate-string {:key-fn dashes->lower-camel})
      (rr/content-type "application/json;charset=UTF-8")))
;; See: io/pedestal/service/http/body_params.clj
(def custom-body-params (body-params/body-params (body-params/default-parser-map :json-options {:key-fn dashes->lower-camel})))

;; FIXME validate input parameters.
(defn clean-params [m]
  (for-map [[k v] m]
           (underscores->dashes k)
           (condp #(%1 %2) (underscores->dashes k)
             #{:relation-limit :limit :offset} (try (Long/parseLong v) (catch Exception e -1))
             #{:type :measure} (underscores->dashes v)
             #{:scale :compact-numbers :debug} (condp re-seq v #"(?i)^(true|1)$" true #"(?i)^(false|0)$" false -1)
             #{:genre} (seq (clojure.string/split v #"\."))
             v)))

(defon-request custom-decode-params
  [request]
  (update-in request [:query-params]
             (fn [m]
               (-> (map-keys underscores->dashes m)
                   (?> (:relation-limit m) update-in [:relation-limit] (try #(Long/parseLong %) (catch Exception e)))
                   (?> (:limit m) update-in [:limit] (try #(Long/parseLong %) (catch Exception e)))
                   (?> (:offset m) update-in [:offset] (try #(Long/parseLong %) (catch Exception e)))
                   (?> (:type m) update-in [:type] underscores->dashes)
                   (?> (:genre m) update-in [:genre] clojure.string/split #"\.")
                   (?> (:measure m) update-in [:measure] underscores->dashes)))))

(def allowed-norms (delay (set (keys @db/norm-map))))
(def allowed-types db/gram-types)
(def allowed-measures (set (conj (keys association-measures-graph) :count)))

(defn view-sources-genre
  "Returns a JSON d3-compatible tree structure of counts by genre.
  Defaults to sources count.
  Checks for valid input, defined as what is available in db/norm-map."
  [request]
  (rr/response ((or (->> request :query-params :norm keyword (get @allowed-norms))
                    :sources)
                @db/norm-map)))

(defn view-genre-similarity
  [request]
  (if-let [genre (-> request :query-params :genre (clojure.string/split #"\."))]
    (rr/response (lm/get-genre-similarities genre))))

(defn view-sources-api [request]
  (rr/response "TODO"))

(defn view-tokens [{:keys [query-params]}]
  (let [{:keys [genre norm] :or {norm :tokens}} query-params] ; TODO :norm should include other measures like tf-idf, dice, etc.
    ;; FIXME actually, norm should not be settable, but only indirectly available through other measures like tf-idf, etc.
    (rr/response (db/get-one-search-token (clean-params query-params) :norm (keyword norm)))))

(defn inclusion-of
  "Modified version of inclusion-of of validateur.

   Returns a function that, when given a map, will validate that the value of the attribute in that map is one of the given.

   Accepted options:

   :blank-message (default:\"can't be blank\"): returned error message if value is not present
   :message (default: \"must be one of: \"): returned error message
                                             (with comma-separated valid values appended)
   :message-fn (default:nil): function to retrieve message with signature (fn [type map attribute & args]).
                              type will be :blank or :inclusion, args will be the set of valid values

   :allow-nil (default: false): should nil values be allowed?
   :in (default: nil): a collection of valid values for the attribute

   Used in conjunction with validation-set:

   (use 'validateur.validation)

   (validation-set
     (presence-of :name)
     (presence-of :age)
     (inclusion-of :team :in #{\"red\" \"blue\"}))"
  [attribute & {:keys [allow-nil in message blank-message message-fn]
                :or {allow-nil false, message "must be one of: ",
                     blank-message "can't be blank"}}]
  (let [f (if (vector? attribute) get-in get)
        blank-msg-fn (fn [m] (if message-fn (message-fn :blank m attribute)
                                blank-message))
        msg-fn (fn [m] (if message-fn (message-fn :inclusion m attribute in)
                          (str message (clojure.string/join ", " in))))]
    (fn [m]
      (let [v (f m attribute)]
        (if (and (nil? v) (not allow-nil))
          [false {attribute #{(blank-msg-fn m)}}]
          (if (contains? in v)
            [true {}]
            [false {attribute #{(msg-fn m)}}]))))))

(defn boolean-validator [attribute]
  (inclusion-of attribute :in #{false true 0 1} :message "must be a Boolean: " ))

(def common-validators
  (delay
   [(boolean-validator :scale)
    (boolean-validator :compact-numbers)
    (boolean-validator :debug)
    (v/inclusion-of :measure :in allowed-measures #_(:FIXME :message (str "must be a String: " (clojure.string/join ", " (map dashes->lower-camel allowed-measures)))))
    (v/inclusion-of :type :in @allowed-types #_(:message (str "must be a String: " (clojure.string/join ", " (map dashes->lower-camel @allowed-types)))))]))

;; TODO Any way to standardize this query in, results out step w/ perhaps custom function/macro?
;; FIXME Follow JSON API recommendations: http://jsonapi.org/format/#url-based-json-api as well as d3 use-cases.
;; TODO Add links to sentence view queries etc. (with link-for Pedestal equiv.)
;; FIXME Collocation view: we want genre to be settable -> default to top-level only (we don't need the genre tree?) and never return genre information (except perhaps as a separate field in the response.)
;; FIXME Genre collocation view: use tree structure, but for one or more fully-specified collocations only! Should this be split into another api path?
(defn view-collocations [{:keys [query-params]}]
  ;; TODO (log-)scaling from 0-100 for display.
  (let [query-params (clean-params query-params)
        v (apply v/validation-set
                 (conj @common-validators
                       ;; TODO :aggregates -- what are valid sets of values?
                       (v/numericality-of :limit :only-integer true :gte 0)
                       (v/numericality-of :offset :only-integer true :gte 0)))
        q (merge {:type :noun-particle-verb
                  :measure :count
                  ;;:aggregates [:count :f-ix :f-xi]
                  :offset 0
                  :limit 80
                  :relation-limit 8
                  :compact-numbers true
                  :scale false
                  :debug false}
                 query-params)]
    (if-let [msgs (seq (v q))]
      (rr/response {:query q
                    :errors msgs})
      (if-let [r (db/query-collocations q)]
        (rr/response (if (:debug query-params)
                       {:results r
                        :query q}
                       r)))))
  #_(try
    (catch Exception e (rr/response {:error "Invalid request"
                                     :exception (pr-str e)
                                     :query query-params}))))

(defn view-collocations-tree [{:keys [query-params]}]
  (let [query-params (merge
                      {:compact-numbers true
                       :scale true}
                      (clean-params query-params))
        v (apply v/validation-set @common-validators)]
    (if-let [msgs (seq (v query-params))]
      (rr/response {:query query-params
                    :errors msgs})
      (if-let [r (db/query-collocations-tree query-params)]
        (rr/response (if (:debug query-params)
                       {:results r
                        :query query-params}
                       r))))))

(defn view-sentences-by-collocation [{:keys [query-params]}]
  ;; validate: html sort order
  (rr/response (db/query-sentences query-params)))

(defn score-sentence [tree sentence]
  (let [tokens (->> tree
                    (mapcat :tokens)
                    (remove (fn [{:keys [pos pos-1 pos-2]}] (or (= pos :symbol) (and (= pos-1 "助詞") (or (= pos-2 "格助詞") (= pos-2 "係助詞"))))))
                    (pmap (fn [m]
                            (let [register-score (db/token-register-score m)
                                  response {:type :token
                                            :tags (:tags m)
                                            :pos (:pos m)
                                            :begin (:begin m)
                                            :end (:end m)
                                            :lemma (:lemma m)
                                            :string (:orth m)}]
                              (if (map? register-score)
                                (merge response register-score)
                                response))))
                    (into []))
        collocations (->> tree
                          natsume-server.collocations/extract-collocations
                          (remove (fn [m] (= (:type m) [:verb :verb]))) ;; FIXME
                          (pmap (fn [m]
                                  (let [record
                                        {:type :collocation
                                         :pos  (:type m)
                                         :tags (:tags m)
                                         :parts (->> m
                                                     :data
                                                     (r/map (fn [part]
                                                              (let [begin (or (:head-begin part) (:tail-begin part))
                                                                    end   (or (:head-end part)   (:tail-end part))
                                                                    pos   (or (:head-pos part)   (:tail-pos part))
                                                                    tags  (or (:head-tags part)  (:tail-tags part))
                                                                    lemma (or (:head-string part) (:tail-string part))]
                                                                {:begin begin
                                                                 :end end
                                                                 :pos pos
                                                                 :tags tags
                                                                 :lemma lemma
                                                                 :string (subs sentence begin end) #_(:head-string part) #_(:tail-string part)})))
                                                     (into []))}
                                        register-score (db/collocation-register-score m)]
                                    (-> record
                                        (assoc :string (clojure.string/join (map :string (:parts record))))
                                        (?> (map? register-score) merge register-score)))))
                          (into []))]
    (concat tokens collocations)))
(defn get-text-register [request]
  ;; FIXME update-in all morphemes all positions with value equal to the end position of the last sentence (or 0 for first sentence) .
  (let [body (->> request :body #_slurp)]
    (if-let [paragraphs (->> body vector text/lines->paragraph-sentences)]
      (let [update-positions (fn [m offset] (-> m (update-in [:begin] + offset) (update-in [:end] + offset)))

            [scored-sentences parsed-tokens]
            (loop [ss (for [paragraph paragraphs sentence paragraph] sentence)
                   offset 0
                   parsed-tokens []
                   results []]
              (if-let [s (first ss)]
                (let [tree (anno/sentence->tree s)
                      token-seq (mapv #(select-keys % [:orth :orth-base :lemma :pos-1 :pos-2 :c-form :c-type :tags]) (mapcat :tokens tree))
                      scored-s (score-sentence tree s)
                      length-s (count s)
                      new-offset (+ offset length-s)
                      nl? (and (< new-offset (count body)) (= \newline (first (subs body new-offset (inc new-offset)))))]
                  (recur (next ss)
                         (+ new-offset (if nl? 1 0))
                         (concat parsed-tokens (if nl? (conj token-seq {:orth "\n" :orth-base "\n" :lemma "\n" :pos-1 "補助記号" :pos-2 "*" :c-form "*" :c-type "*"}) token-seq))
                         (concat results
                                 (map (fn [m] (case (:type m)
                                               :token (update-positions m offset)
                                               :collocation (update-in m [:parts]
                                                                       (fn [parts]
                                                                         (mapv (fn [part]
                                                                                 (update-positions part offset))
                                                                               parts)))))
                                      scored-s))))
                [results (vec parsed-tokens)]))

            bad-morphemes (->> scored-sentences
                               (r/filter #(or (:register-score %) (:stats %) #_(and (:stats %) #_(> (-> % :stats :mi) 5.0))))
                               (into []))]
        (rr/response (if (:debug (:query-params (clean-params request)))
                       {:results bad-morphemes :parsed-tokens parsed-tokens :debug {:body body :parsed paragraphs}}
                       {:results bad-morphemes :parsed-tokens parsed-tokens})))
      (rr/response {:results nil :message "invalid request" :request request}))))

(comment (e! (get-text-register {:body "実験をやるよ。"}))
         (e! (get-text-register {:body "第１節　介護保険法の概要"}))
         "昔では男は仕事をする役割がある"
         (set! *print-length* 10)
         (e! (->> (get-text-register {:body "実験をやるよ。"}) :body :results (filter #(and (= :collocation (:type %)) (= false (:found? %)))) (map #(select-keys % [:found? :stats :string :register-score]))))
         (pprint (get-text-register {:body "人々の行動はそれぞれの国の文化や慣習、考え方によって違うことがあります。"}))
         (pprint (get-text-register {:body "実験をやる。そうですね。"})))

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
     ^:interceptors [#_(body-params/body-params) #_custom-body-params json-interceptor] ; All responses under /api should be avaliable in JSON format.
     ["/sources" {:get view-sources-api}
      ["/genre" {:get view-sources-genre}
       ["/similarity" {:get view-genre-similarity}]]]
     ["/tokens" {:get view-tokens}]
     ["/sentences"
      ["/collocations" ^:interceptors [custom-decode-params] {:get view-sentences-by-collocation}]]
     ["/collocations" {:get view-collocations}
      ["/tree" {:get view-collocations-tree}]]
     ["/errors"
      ["/register" {:post get-text-register}]]]
    ;; FIXME look into chat client app/templates loading! https://github.com/pedestal/samples/blob/master/chat/chat-client/project.clj
    ["/:file" {:get get-file :post list-resources}
     ;; TODO: ClojureScript + d3 visualizations utilizing the full API
     ;; Set default interceptors for paths under /
     ^:interceptors [(body-params/body-params) bootstrap/html-body]]]])

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
              ;;::bootstrap/allowed-origins ["scheme://host:port"]

              ;; Root for resource interceptor that is available by default.
              ::bootstrap/resource-path "/public"
              ;;::bootstrap/file-path "/"
              ;; Either :jetty or :tomcat (see comments in project.clj
              ;; to enable Tomcat)
              ;;::bootstrap/host "localhost"
              ::bootstrap/type :jetty
              ::bootstrap/port (cfg/opt :server :port)})
