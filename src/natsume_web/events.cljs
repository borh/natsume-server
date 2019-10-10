(ns natsume-web.events
  (:require
    [re-frame.core :refer [reg-event-db reg-event-fx reg-fx inject-cofx dispatch]]
    [re-frame.std-interceptors :refer [debug trim-v]]
    [goog.dom :as dom]
    [goog.net.cookies :as cookies]
    [ajax.core :as ajax]
    #_[taoensso.sente :as sente]
    [secretary.core :as secretary]
    [day8.re-frame.http-fx]
    [day8.re-frame.async-flow-fx]
    [day8.re-frame.tracing :refer-macros [fn-traced]]
    [akiroz.re-frame.storage :as storage]
    #_[natsume-web.communication :as comm]
    [natsume-web.config :refer [debug-enabled? api-url files-url]]
    [natsume-web.utils :as utils]
    [clojure.string :as string]
    [clojure.spec.alpha :as s])
  (:require-macros [natsume-web.events :refer [transit-bridge]]))

(def middleware
  [trim-v])

(storage/reg-co-fx! :natsume-web-db                         ;; local storage key
                    {:fx   :store                           ;; re-frame fx ID
                     :cofx :store})                         ;; re-frame cofx ID

;; TODO
(comment
  (reg-event-fx
    :read-foo-store-bar
    [(inject-cofx :store)]
    (fn [{:keys [store]} _]
        (when debug-enabled?
              (print (:foo store)))
        {:store (assoc store :bar "qux")})))

(defn transit-query [query update-fx]
      {:method          :post
       :uri             api-url
       :params          query
       :timeout         60000
       :format          (ajax/transit-request-format)
       :response-format (ajax/transit-response-format)
       :on-success      [update-fx]
       :on-failure      [:transit-query-failure]})

(reg-event-fx
  :transit-query-failure
  middleware
  (fn-traced [{:keys [db]} [msg]]
             {:db       (assoc db :error/message (or (get-in msg [:response :error/message])
                                                     (str msg)))
              :dispatch [:set/fulltext-state nil]}))

(reg-event-db
  :set/error-message
  middleware
  (fn-traced [db [new-state]]
             (assoc db :error/message new-state)))

(transit-bridge [:server/ping nil])

(defn boot-flow
      []
      {:first-dispatch [:server/ping #_:user/set-cookies]
       #_:rules #_[{:when       :seen?
                    :events     [:sente/auth-success]
                    :dispatch-n [[:sente/connect]
                                 #_[:get/sources-genre]
                                 #_[:get/sentences-collocations]
                                 #_[:get/sentences-tokens]
                                 #_[:get/tokens-tree]
                                 #_[:get/tokens-similarity]
                                 #_[:get/tokens-nearest-tokens]
                                 #_[:get/tokens-similarity-with-accuracy]
                                 #_[:get/tokens-tsne]
                                 #_[:get/collocations-collocations]
                                 #_[:get/collocations-tree]
                                 #_[:get/suggestions-tokens]
                                 #_[:get/errors-register]
                                 #_[:get/topics-infer]]
                    :halt?      false}]})

#_(reg-event-fx
    :user/set-cookies
    middleware
    (fn-traced [{:keys [db]} [_]]
               (if-let [auth-token (cookies/get "auth-token")]
                       {:db       (assoc db
                                         :user/auth-token auth-token
                                         :user/csrf-token js/csrfToken)
                        :dispatch [:api/authenticate]})))

#_(reg-event-fx
    :sente/connect
    middleware
    (fn-traced [{:keys [db]} [_]]
               {:db       (assoc db :sente/connection-status (:open? @(:state @comm/!socket)))
                :dispatch [:sente/started]}))

#_(reg-event-fx
    :sente/started
    middleware
    (fn-traced [_ _]
               (when debug-enabled?
                     (println "Connected!"))
               {}))

(def ring-response-format
  (-> (ajax/json-response-format {:keywords? true})
      (update :read (fn [original-handler]
                        (fn [response-obj]
                            {:headers (js->clj (.getResponseHeaders response-obj))
                             :body    (original-handler response-obj)
                             :status  (.getStatus response-obj)})))))

#_(reg-event-fx
    :api/authenticate
    (fn-traced [{:keys [db]} _]
               ;; Try to use the token to determine its validity:
               (cond
                 ;; FIXME We should first *try* to use the token; it might be invalid!
                 (:user/auth-token db)
                 (if (comm/create-socket! (:user/auth-token db))
                   {:db (assoc db :user/account-valid true)})

                 (and (:user/username db) (:user/password db))
                 {:http-xhrio {:method            :post
                               :uri               (str api-url "/login")
                               :params            {:username (:user/username db)
                                                   :password (:user/password db)}
                               :timeout           1000
                               :format            (ajax/json-request-format)
                               :response-format   ring-response-format
                               :on-success        [:sente/auth-success]
                               :on-failure        [:sente/auth-failure]
                               :with-credentials? false}})))

#_(reg-event-fx
    :sente/init
    (fn-traced [{:keys [db]} _]
               (let []
                    (comm/create-socket! (:user/auth-token db)))
               {}))

#_(reg-event-db
    :sente/auth-success
    (fn-traced [db [_ {:keys [headers body]}]]
               (let [auth-token (:token body)]
                    (cookies/set "auth-token" auth-token)
                    (when (= :login (:page/active db))
                          (secretary/dispatch! "/index.html")
                          (utils/redirect "index.html"))
                    (when-not (= :login (:page/active db))
                              (comm/create-socket! auth-token))
                    (assoc db
                           :user/auth-token auth-token
                           :user/account-valid true))))

;; https://github.com/metosin/reitit/issues/303

#_(reg-event-fx
    :sente/auth-failure
    (fn-traced [{:keys [db]} [_ {:keys [headers body]}]]
               (when debug-enabled?
                     (println "auth-failure" headers body))
               (cookies/remove "auth-token")
               (comm/reconnect!)
               {:db (assoc db :user/account-valid false)}
               ;; Rolling timeout with reset?
               #_(let [cookie-token (cookies/get "authtoken")]
                      (comm/create-socket! cookie-token)
                      {:db (assoc db
                                  :user/auth-token cookie-token
                                  :user/account-valid false)})))

;; API

(s/def ::token (s/keys :un-req [:lemma :orth-base :pos]))

(s/def :sources/genre (s/nilable string?))
(s/def :sentences/collocations (s/map-of keyword? string?))
(s/def :sentences/tokens (s/map-of keyword? string?))

(def sources-api
  {:sources/genre nil})

(def tokens-api
  {:sentences/collocations          nil
   :sentences/tokens                nil
   :tokens/tree                     nil
   :tokens/similarity               nil
   :tokens/nearest-tokens           nil
   :tokens/similarity-with-accuracy nil
   :tokens/tsne                     nil
   :collocations/collocations       nil
   :collocations/tree               nil
   :suggestions/tokens              nil})

(def text-api
  {:errors/register nil
   :topics/infer    nil})

(def fulltext-api
  {:fulltext/query             (if debug-enabled? "しかし" "")
   :fulltext/matches           nil
   :fulltext/file              nil
   :fulltext/limit             100
   :fulltext/page              0                            ;; offset is page*limit
   :fulltext/genre-column      true
   :fulltext/title-column      true
   :fulltext/author-column     false
   :fulltext/year-column       false
   :fulltext/speech-tag        true
   :fulltext/quotation-tag     true
   :fulltext/document-selected nil
   :fulltext/document-text     nil
   :fulltext/document-show     false})

(def input-api
  {:user/auth-token       nil
   :user/username         nil
   :user/password         nil
   :user/account-valid    nil
   :user/id               nil

   :user/text             (if debug-enabled? "入力テキストを解析する。" nil)
   :user/unit-type        :suw
   :user/features         [:orth]
   :user/token            (if debug-enabled? "花" nil)
   :user/extra-token      (if debug-enabled? "菊" nil)
   :user/genre            (if debug-enabled? "白書|書籍.*" "*")
   :user/limit            5
   :user/html             true
   :user/norm             :tokens
   :user/collocation      {:string-1 "花" :string-1-pos "noun"
                           :string-2 "を" :string-2-pos "particle"
                           :type     :noun-particle-verb}
   :user/collocation-tree {:string-1 "花" :string-1-pos "noun"
                           :string-2 "を" :string-2-pos "particle"
                           :string-3 "見る" :string-3-pos "verb"
                           :type     :noun-particle-verb}
   :user/selected-topic   nil})

(reg-event-fx
  :boot
  (fn-traced [_ _]
             (let [db (merge
                        {;; :sente/connection-status nil
                         :server/ping nil
                         :error/message nil
                         :page/active (if (dom/getElement "login")
                                        :login
                                        :app)
                         #_{0 {"a" 0.90 "b" 0.05 "c" 0.04 "d" 0.01}
                            1 {"p" 1.0}
                            2 {"k" 9922 "l" 22}}}
                        sources-api
                        tokens-api
                        text-api
                        fulltext-api
                        input-api)]
                  {:db         db
                   :async-flow (boot-flow)})))

;; TODO parameterize state

(reg-event-db
  :set/active-page
  middleware
  (fn-traced [db [new-state]]
             (secretary/dispatch! (str "/" (name new-state)))
             (assoc db :page/active new-state)))

(reg-event-db
  :set/server-ping
  middleware
  (fn-traced [db [new-state]] (assoc db :server/ping new-state)))

#_(reg-event-db
    :set/sente-connection-status
    middleware
    (fn-traced [db [new-state]] (assoc db :sente/connection-status new-state)))

(reg-event-db
  :set/user-account-valid
  middleware
  (fn-traced [db [new-state]] (assoc db :user/account-valid new-state)))

(reg-event-db
  :set/user-auth-token
  middleware
  (fn-traced [db [new-state]] (assoc db :user/auth-token new-state)))

(reg-event-db
  :set/user-username
  middleware
  (fn-traced [db [new-state]] (assoc db :user/username new-state)))

(reg-event-db
  :set/user-password
  middleware
  (fn-traced [db [new-state]] (assoc db :user/password new-state)))

(reg-event-db
  :set/user-text
  middleware
  (fn-traced [db [new-state]] (assoc db :user/text new-state)))

(reg-event-db
  :set/user-unit-type
  middleware
  (fn-traced [db [new-state]] (assoc db :user/unit-type new-state)))

(reg-event-db
  :set/user-features
  middleware
  (fn-traced [db [new-state]] (assoc db :user/features new-state)))

(reg-event-db
  :set/user-token
  middleware
  (fn-traced [db [new-state]] (assoc db :user/token new-state)))

(reg-event-db
  :set/user-extra-token
  middleware
  (fn-traced [db [new-state]] (assoc db :user/extra-token new-state)))

(reg-event-db
  :set/user-genre
  middleware
  (fn-traced [db [new-state]] (assoc db :user/genre new-state)))

(reg-event-db
  :set/user-limit
  middleware
  (fn-traced [db [new-state]] (assoc db :user/limit new-state)))

(reg-event-db
  :set/user-html
  middleware
  (fn-traced [db [new-state]] (assoc db :user/html new-state)))

(reg-event-db
  :set/user-norm
  middleware
  (fn-traced [db [new-state]] (assoc db :user/norm new-state)))

(reg-event-db
  :set/user-collocation
  middleware
  (fn-traced [db [new-state]] (assoc db :user/collocation new-state)))

(reg-event-db
  :set/user-collocation-tree
  middleware
  (fn-traced [db [new-state]] (assoc db :user/collocation-tree new-state)))

(reg-event-db
  :set/user-selected-topic
  middleware
  (fn-traced [db [new-state]] (assoc db :user/selected-topic new-state)))

(reg-event-db
  :set/fulltext-query
  middleware
  (fn-traced [db [new-state]] (assoc db :fulltext/query new-state)))

(reg-event-db
  :set/fulltext-state
  middleware
  (fn-traced [db [new-state]] (assoc db :fulltext/state new-state)))

(reg-event-db
  :set/fulltext-limit
  middleware
  (fn-traced [db [new-state]] (assoc db :fulltext/limit new-state)))

(reg-event-fx
  :set/fulltext-page
  middleware
  (fn-traced [{:keys [db]} [new-state]]
             {:db       (assoc db :fulltext/page new-state)
              :dispatch [:get/fulltext-matches]}))

;; Toggle columns
(reg-event-db
  :toggle/fulltext-genre-column
  middleware
  (fn-traced [db [_]] (update db :fulltext/genre-column not)))

(reg-event-db
  :toggle/fulltext-title-column
  middleware
  (fn-traced [db [_]] (update db :fulltext/title-column not)))

(reg-event-db
  :toggle/fulltext-author-column
  middleware
  (fn-traced [db [_]] (update db :fulltext/author-column not)))

(reg-event-db
  :toggle/fulltext-year-column
  middleware
  (fn-traced [db [_]] (update db :fulltext/year-column not)))

(reg-event-db
  :toggle/fulltext-speech-tag
  middleware
  (fn-traced [db [_]] (update db :fulltext/speech-tag not)))

(reg-event-db
  :toggle/fulltext-quotation-tag
  middleware
  (fn-traced [db [_]] (update db :fulltext/quotation-tag not)))

;;

(comment
  (reg-event-db
    :set/user-misc
    middleware
    (fn-traced [db [new-state]] (assoc db :user/misc new-state))))


(comment
  (reg-event-db
    :update-text-topics
    middleware
    (fn-traced [db [results]]
               (let [topics
                     (->> results
                          :results
                          (reduce
                            (fn [a {:keys [id prob tokens]}]
                                (assoc a id {:prob prob :token-probs (zipmap tokens (repeat 1.0))}))
                            {}))
                     first-topic (-> results :results first :id)]
                    (assoc db :text-topics topics :selected-topic first-topic)))))

;;

(transit-bridge [:sources/genre :sources])

(transit-bridge [:sentences/collocations
                 (merge {:limit (:user/limit input-api)
                         :html  (:user/html input-api)}
                        (:user/collocation-tree input-api))])

(transit-bridge [:sentences/tokens {:lemma (:user/token input-api) :limit (:user/limit input-api) :html (:user/html input-api)}])

(transit-bridge [:tokens/tree {:lemma (:user/token input-api) :norm (:user/norm input-api)}])

(transit-bridge [:tokens/similarity [:suw [:orth] (:user/token input-api) (:user/extra-token input-api)]])

(transit-bridge [:tokens/nearest-tokens [:suw [:orth] (:user/token input-api) 5]])

(transit-bridge [:tokens/similarity-with-accuracy [:suw [:orth] (:user/token input-api) 0.8]])

(transit-bridge [:collocations/collocations (merge {:limit (:user/limit input-api)}
                                                   (:user/collocation input-api))])

(transit-bridge [:collocations/tree (:user/collocation-tree input-api)])

(transit-bridge [:tokens/tsne [:suw [:orth] (:user/token input-api) 5 2]])

(transit-bridge [:suggestions/tokens {:lemma (:user/token input-api)}])

(transit-bridge [:errors/register (:user/text input-api)])

(transit-bridge [:topics/infer {:unit-type :suw :features [:orth] :text (:user/text input-api)}])

;;

(reg-event-fx
  :get/sources-by-sentence-id
  middleware
  (fn-traced [_ [query]]
             {:http-xhrio (transit-query [:sources/sentence-id query] :set/fulltext-document-text)
              :dispatch   [:toggle/fulltext-document-show]}))

(reg-event-db
  :toggle/fulltext-document-show
  middleware
  (fn-traced [db [_]]
             (update db :fulltext/document-show not)))

(reg-event-db
  :set/fulltext-document-text
  middleware
  (fn-traced [db [data]]
             (assoc db
                    :fulltext/document-text
                    (->> (:text data)
                         string/split-lines
                         (map (fn [s] ^{:key (gensym "p-")} [:p s])))
                    :fulltext/document-author (:author data)
                    :fulltext/document-title (:title data)
                    :fulltext/document-year (:year data)
                    :fulltext/document-genre (:genre data))))

(reg-event-fx
  :get/fulltext-matches
  middleware
  (fn-traced [{:keys [db]} _]
             {:db         (assoc db :fulltext/state :loading)
              :http-xhrio (transit-query [:fulltext/matches
                                          {:query       (:fulltext/query db)
                                           :genre       (:user/genre db)
                                           :limit       (:fulltext/limit db)
                                           :offset      (* (:fulltext/page db) (:fulltext/limit db))
                                           :remove-tags (cond-> #{}
                                                                (not (:fulltext/speech-tag db)) (conj :speech)
                                                                (not (:fulltext/quotation-tag db)) (conj :quotation))}]
                                         :set/fulltext-matches)}))

(reg-event-db
  :set/fulltext-matches
  middleware
  (fn-traced [db [data]]
             (assoc db
                    ;; TODO Not ideal, but this reverse transformation works...
                    :fulltext/matches (map (fn [m]
                                               (update m :before string/reverse))
                                           (:matches data))
                    :fulltext/total-count (:total-count data)
                    :fulltext/patterns (sort-by :frequency >
                                                (for [[[k v] i]
                                                      (zipmap (:patterns data)
                                                              (range (count (:patterns data))))]
                                                     {:pattern k :frequency v :idx i}))
                    :fulltext/file (str files-url (:file data))
                    :fulltext/state :loaded)))
