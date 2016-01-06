(ns natsume-server.endpoint.api-schema-docs
  (:require [clojure.string :as str]
            [schema.core :as s]
            [bidi.bidi :as bidi]
            [yada.yada :as yada]
            [cheshire.core :as json]
            [hiccup.page :refer [html5]]))

;; API printing

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
           (str/join "' OR '")
           (format "'%s'"))))

  schema.core.Predicate
  (schema-print [s]
    (case (some-> s :pred-name str)
      "keyword?" "String"))

  clojure.lang.Keyword
  (schema-print [s] (name s))

  java.lang.String
  (schema-print [s] s)

  clojure.lang.PersistentVector
  (schema-print [s] (format "Vec( %s )" (str/join ", " (mapv schema-print (sort s)))))

  clojure.lang.PersistentHashSet
  (schema-print [s] (str (schema-print (class (first s))) " (" (str/join " | " (map schema-print s)) ")"))

  java.lang.Class
  (schema-print [s] (schema-print (last (str/split (.getName s) #"\."))))

  nil
  (schema-print [s] "nil"))

;; API Schema HTML generation

(defn describe-routes
  "An example of the kind of thing you can do when your routes are data"
  [api]
  (for [{:keys [path handler]} (bidi/route-seq (bidi/routes api))]
    {:path (apply str path)
     :description (get-in handler [:properties :doc/description])
     :handler handler}))

(defn index-page [api port !examples server-address]
  (yada/yada
   (merge
    (yada/as-resource
     (html5
      {:encoding "UTF-8"}
      [:link {:type "text/css" :href "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.6/css/bootstrap.min.css" :rel "stylesheet" :crossorigin "anonymous" :integrity "sha384-1q8mTJOASx8j1Au+a5WDVnPi2lkFfwwEAa8hDDdjZlpLegxhjVME1fgjWPGmkzs7"}]
      [:div.container-fluid
       [:div.row
        [:div.col-xs-offset-1.col-xs-10
         [:div.page-header [:h1 "natsume-server API Documentation"]]
         [:p "Version 1.0 © " [:a {:href "https://hinoki-project.org/natsume"} "Natsume@Hinoki Project"]]
         (for [{:keys [path description handler]} (describe-routes api)
               :let [api-root server-address #_(format "http://localhost:%d" port)
                     api-url (format "%s%s" api-root path)]]
           [:div.panel.panel-default
            [:div.panel-heading [:h2.panel-title api-root [:b path]]]
            [:div.panel-body
             (for [method (clojure.set/intersection #{:get :post} (:allowed-methods handler))
                   :let [meth (str/upper-case (str (name method)))
                         param-type (case method :get :query :post :body :head nil :options nil)
                         {:keys [description summary consumes produces] :as m} (-> handler :resource :methods method)]]
               [:div
                [:h4 summary " " [:span.label.label-default meth]]
                [:dl.dl-horizontal
                 [:dt "Description"] [:dd description]
                 ;; [:dt "Curl"] [:dd {:style "font-family:monospace;"} (format "curl -i -X %s http://localhost:%d%s" meth port path)]
                 [:dt "Input media types"]  [:dd (schema-print (apply s/enum (mapv :name (map :media-type consumes))))]
                 [:dt "Output media types"] [:dd (schema-print (apply s/enum (mapv :name (map :media-type produces))))]
                 [:dt "Parameters"]
                 [:dd
                  (if-let [ps (some-> handler :resource :methods method :parameters)]
                    (case method
                      :get
                      [:table.table.table-striped
                       [:thead [:td [:b "Field"]] [:td [:b "Value schema"]]]
                       [:tbody
                        (for [[k v] (into (sorted-map-by (fn [a b] (compare (schema-print a) (schema-print b)))) (param-type ps))]
                          [:tr [:td (schema-print k)] [:td (schema-print v)]])]]
                      :post
                      [:table.table.table-striped
                       [:thead
                        [:td [:b "Body"]]
                        [:td (schema-print (param-type ps))]]]))]
                 ;; Examples
                 [:dt "Example query"]
                 [:dd
                  (if-let [example (get @!examples summary)]
                    [:div
                     (for [[example-type example-query] example]
                       (->> example-query
                            sort
                            (reduce
                             (fn [a [k v]]
                               (conj a [:div.form-group.form-group-sm
                                        [:label.col-sm-2.control-label (name k)]
                                        [:div.col-sm-2 [:input.form-control {:type "text" :name (name k) :placeholder (str v) :value (if-not empty? v (str v))}]]]))
                             [[:div.form-group.form-group-sm [:div.col-sm-offset-2.col-sm-2 [:button.btn.btn-default {:type "submit"} "Execute query"]]]])
                            (into [:form.form-horizontal
                                   {:action api-url
                                    ;;:target "_blank"
                                    :method (case example-type :body "post" :query "get")
                                    :enctype (case example-type :body "text/plain" :query "application/x-www-form-urlencoded")}])))])]]])]])]]
       ;; Remove empty form fields:
       [:script {:src "//code.jquery.com/jquery-1.11.3.min.js"}]
       [:script "$('form').submit(function() {
    $(':input', this).each(function() {
        this.disabled = !($(this).val());
    });
});"]]))
    {:produces {:media-type "text/html" :charset "UTF-8"}})))
