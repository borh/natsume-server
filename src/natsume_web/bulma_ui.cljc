(ns natsume-web.bulma-ui
  #?(:cljs (:require [clojure.spec.alpha :as s]))
  #?(:clj (:require [clojure.spec.alpha :as s]
                    [hiccup.page :refer [html5]]
                    [clj-time.format :as f]
                    [clj-time.local :as l]
                    [crypto.random :as random])))

;; Page layout and header

#?(:clj
   (defn header
     [{:keys [author description title]}]
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"}]
      [:link {:rel "stylesheet" :href "assets/bulma/css/bulma.css" :type "text/css"}]
      [:link {:rel "stylesheet" :href "assets/font-awesome/css/all.min.css" :type "text/css"}]
      [:link {:rel "stylesheet" :href "assets/balloon-css/balloon.min.css" :type "text/css"}]
      [:link {:rel "stylesheet" :href "public/app.css" :type "text/css"}]
      (if author [:meta {:name "author" :content author}])
      (if description [:meta {:name "description" :content description}])
      (if title [:title title])]))

(s/def ::icon keyword?)

(defn fa-icon
  ([icon]
   (fa-icon icon nil))
  ([icon {:keys [size on-click variation] :or {variation "fas"}}]
   [:span (cond-> {:class "icon"}
            size (update :class str " " (name size))
            on-click (assoc :on-click on-click))
    [:i {:class (str variation " fa-" (name icon))}]]))

(s/def ::nav-items (s/map-of
                    (s/alt :left :left :center :center :right :right)
                    (s/coll-of vector?)))

(defn navbar
  ;; FIXME should be able to use this from clj, but need a way to generate nav items in clj (change part of views to cljc...)
  ;; FIXME does this even make sense with SPA (would need a different URL for login <-> page)
  [{:keys [app-name app-url
           nav-items] ;; TODO lang
    :or {app-url "#"}}]
  (let [navbar-brand (cond-> [:div.navbar-brand
                              [:a.navbar-item.is-bold {:href app-url} app-name]]
                       (:left nav-items) (into (:left nav-items))
                       true (conj [:div.navbar-burger.burger {:data-target "navMenu"} [:span] [:span] [:span]]))
        navbar-menu (cond-> [:div.navbar-menu {:id "navMenu"}]
                      (:center nav-items) (conj (into [:div.navbar-start] (:center nav-items)))
                      (:right nav-items)  (conj (conj (into [:div.navbar-end] (:right nav-items))
                                                      [:a.navbar-item (fa-icon :language) "　EN"])))]
    [:nav.navbar.has-shadow
     [:div.container
      navbar-brand
      navbar-menu]]))

#?(:clj
   (defn footer [{:keys [author app-name]}]
     (let [date-updated (l/local-now)
           year-updated (f/unparse (f/formatter "yyyy") date-updated)]
       [:footer.footer.is-sans
        [:div.columns
         [:div.column.is-two-thirds
          [:div.container.has-text-centered
           [:p (fa-icon :copyright) year-updated " "
            [:span {:itemprop "author"
                    :itemscope ""
                    :itemtype "http://schema.org/Person"}
             [:span {:itemprop "name"} author]]]
           [:p
            app-name " is licensed under the " [:a {:href "http://opensource.org/licenses/mit-license.php"} "MIT License (MIT)"]]
           [:p "Made with " [:a {:href "https://clojure.org/"} "Clojure/ClojureScript"] " and " [:a {:href "https://github.com/Day8/re-frame"} "re-frame"] " (" [:a {:href "https://github.com/borh/natsume-web"} "source"] ")"]
           [:p "Styled with " [:a {:href "http://bulma.io/"} "Bulma"]
            " and " [:a {:href "http://fontawesome.io/"} "FontAwesome"]]]]
         [:div.column
          [:div.container
           [:p (fa-icon :home) [:a {:href "https://hinoki-project.org/"} " Hinoki Project"]]
           [:p
            (fa-icon :envelope) " first name @ lang.osaka-u.ac.jp"]
           [:p (fa-icon :github-square {:variation "fab"})
            [:a {:href "https://github.com/borh"}
             " borh"]]
           [:p (fa-icon :twitter-square {:variation "fab"})
            [:a {:href "https://twitter.com/bhodoscek"}
             " bhodoscek"]]]]]
        [:div.columns
         [:div.column.has-text-centered
          [:p "Updated "
           [:span {:itemprop "datePublished"
                   :content (f/unparse (f/formatter "yyyy-MM-dd") date-updated)}
            (f/unparse (f/formatter-local "yyyy/M/d@HH:kk") (l/to-local-date-time date-updated))]]]]])))

#?(:clj
   (defn page
     [{:keys [body
              lang author description title
              app-name app-url nav-items csrf-token]
       :or {lang "ja"
            body [:div#app]
            csrf-token (random/base64 60)}}]
     (html5
      {:lang lang :encoding "UTF-8"}
      (header {:author author :description description :title title})
      [:body
       [:div.container-flexible
        (if nav-items
          (navbar {:app-name app-name :app-url app-url :nav-items nav-items}))

        body

        [:div#learn]

        (footer {:author author :app-name app-name})

        #_[:div#sente-csrf-token csrf-token]
        [:script {:type "text/javascript"}
         (format "var csrfToken = '%s';" csrf-token)]
        [:script {:src "public/js/main.js" :type "text/javascript"}]]])))

;; Components

(defn checkbox [{:keys [label model on-change]}]
  [:label.checkbox
   [:input
    {:type "checkbox"
     :checked @model
     :on-change on-change}]
   label])

(defn tree-branch [label children]
  (let [branch-id (gensym label)
        dom [:li
             [:input {:type "checkbox" :checked "checked" :id branch-id}
              [:label.tree_label {:for branch-id} label]]]]
    (if children
      (conj dom (map tree-branch children))
      dom)))

(defn selectable-tree [tree]
  [:ul.tree
   [:li
    [:input {:type "checkbox" :checked "checked" :id "c1"}
     [:label.tree_label {:for "c1"} "0"]]]])

(s/def ::form-type (s/or :text     "text"
                         :email    "email"
                         :password "password"
                         :username "username"))

(defn form [{:keys [model form-type label label-tooltip placeholder load-state icon on-change attrs has-addons is-horizontal]
             :or {placeholder ""
                  form-type "text"
                  load-state (atom nil)
                  attrs {}}
             :as opts}]
  (let [p-classes "control"
        input-classes "input"
        attrs (if on-change
                (assoc attrs :on-change on-change)
                attrs)]
    [:div {:class (cond-> "field"
                    has-addons (str " has-addons")
                    is-horizontal (str " is-horizontal"))}
     [:div.field-label.is-normal
      [:label.label
       (if label-tooltip ;; TODO: replace with https://github.com/Wikiki/bulma-tooltip/blob/master/tooltip.sass
         {:data-balloon-length "large"
          :data-balloon label-tooltip
          :data-balloon-pos "right"})
       label]]
     [:div.field-body
      [:p {:class (cond-> p-classes
                    (= :loading @load-state) (str " is-loading")
                    icon (str " has-icon"))}
       [:input (merge attrs
                      {:type form-type
                       :class (case @load-state
                                :loading input-classes
                                :loaded (str input-classes " is-success")
                                :success (str input-classes " is-success")
                                input-classes)
                       :placeholder placeholder
                       :default-value @model})]
       (if icon
         (fa-icon icon {:size :is-small}))]]]))

(s/def ::hiccup (s/or :string  string?
                      :element (s/cat :tag keyword?
                                      :attrs (s/? map?)
                                      :content (s/* ::hiccup))))
(s/def ::on-click fn?)
(s/def ::label string?)
(s/def ::connection-state boolean?)
(s/def ::tooltip string? #_(s/or :string string? :hiccup ::hiccup))
(s/def ::default-class string?)
(s/def ::attrs (s/map-of keyword? string?))

(defn button [{:keys [label on-click
                      default-class attrs load-state disabled? tooltip tooltip-pos icon]
               :or {load-state (atom nil)
                    disabled? false
                    default-class "button"
                    attrs {}
                    tooltip-pos "down"}
               :as opts}]
  (let [tooltip-attrs (if tooltip
                        {:data-balloon-length "medium"
                         :data-balloon tooltip
                         :data-balloon-pos tooltip-pos
                         :data-balloon-visible ""}
                        {})]
    [:a (merge attrs
               tooltip-attrs
               {:class
                (cond-> default-class
                  (= :loading @load-state) (str " is-primary is-active")
                  (= :loaded  @load-state) (str " is-success")
                  disabled? (str " disabled"))
                :on-click on-click})
     (when icon icon)
     label]))

(s/fdef button :args (s/keys :req [::label ::on-click]
                             :opt [::default-class ::attr ::load-state ::connection-state ::tooltip]))

(s/def ::state-level (s/or :primary "is-primary"
                           :info    "is-info"
                           :success "is-success"
                           :warning "is-warning"
                           :danger  "is-danger"))

(s/def ::body (s/or :string string? :hiccup ::hiccup))
(s/def ::heading string?)

(defn notification [{:keys [state-level heading body]}]
  (let [default-class "notification"]
    [:div
     {:class (if state-level
               (str default-class " " state-level)
               default-class)}
     [:button.delete]
     (if heading
       [:p.title.is-3 heading])
     body]))

(s/fdef notification :args (s/keys :req [::body] :opt [::state-level ::heading]))
