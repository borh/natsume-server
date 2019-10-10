(ns natsume-web.views
  (:require
   [goog.string :as gstring]
   [goog.string.format]
   [natsume-web.subs]
   [re-frame.core :refer [subscribe dispatch]]
   [clojure.string :as str]))

(defn handle-loading-state [state]
  (cond (nil? state) nil
        (= state :loading) :loading
        (= state :chsk/timeout) :error))

;; # Components

;; ## Stats

(defn scaled-bar
  [prob scaling-factor]
  [:svg {:width 20 :height 10}
   [:rect {:width 20 :height 10 :style {:fill "#d3d3d3"}}]
   [:rect {:width (* 20 (/ prob scaling-factor)) :height 10}]])

(defn text-bar-graph
  "Displays an ordered list of text and numeric score pairs. The input should be presorted and shortened to desired length."
  ([token-probs width]
   (text-bar-graph token-probs width false))
  ([token-probs width common-scale]
   (let [scaling-factor (or common-scale (apply max (vals token-probs)))]
     [:table.table.table-sm
      [:thead [:tr [:th "prob"] [:th "token"]]]
      [:tbody
       (for [[token prob] token-probs]
         ^{:key (gensym "text-bar-")}
         [:tr
          [:td.bar-width.text-xs-center
           (scaled-bar prob scaling-factor)]
          [:td token]])]])))

;; ## Topic models

(defn topic-model-box
  []
  (let [text-topics (subscribe [:topics/infer])
        selected-topic (subscribe [:user/selected-topic])]
    (fn []
      (if (seq @text-topics)
        [:div.card
         #_[:div.card-header
            [:ul.nav.nav-tabs.card-header-tabs.pull-xs-left
             (mapv
              (fn [[topic-id {:keys [prob]}]] ;; TODO prob
                (println @text-topics @selected-topic topic-id prob)
                ^{:key (str "topic-header-" topic-id)}
                [:li.nav-item
                 [:a
                  (if (= @selected-topic topic-id)
                    {:class "nav-link active"}
                    {:class "nav-link"
                     :on-click (fn [e] (dispatch [:set/user-selected-topic topic-id]))})
                  (scaled-bar prob 1.0)
                  (str topic-id)]])
              @text-topics)]]
         [:div.card-block
          [text-bar-graph (get-in @text-topics [@selected-topic :token-probs]) 100]
          [:a.btn.btn-primary {:on-click (fn [e] (dispatch [:get/topics-infer]))}
           "Update"]]]
        [:div.card
         [:div.card-block
          [:a.btn.btn-primary {:on-click (fn [e] (dispatch [:get/topics-infer]))}
           "Infer topics"]]]))))

;; ## Basic input box

;; TODO: the following purports to have solved the IME input issues:
;; https://github.com/aprilandjan/react-starter/blob/test/search-input/src/components/SearchInput.js

(defn input-box []
  (let [limit (subscribe [:user/limit])
        html (subscribe [:user/html])
        norm (subscribe [:user/norm])
        unit-type (subscribe [:user/unit-type])
        features (subscribe [:user/features])
        token (subscribe [:user/token])
        token-results (subscribe [:tokens/tree])
        tokens-nearest (subscribe [:tokens/nearest-tokens])]
    (fn []
      (letfn [(search [query]
                (dispatch [:get/tokens-tree {:orth query :norm @norm}])
                (dispatch [:get/tokens-nearest-tokens @unit-type @features query @limit]))]
        [:div.card
         [:div.card-header
          [:ul.nav.nav-tabs.card-header-tabs.pull-xs-left
           [:li.nav-item "Search"]]]
         [:div.card-block
          {:class (str "form-group label-floating "
                       (case token-results
                         :success "has-success"
                         :failure "has-error"
                         nil))}
          [:label.control-label
           (case token-results
             :success "Search successful"
             :failure "Search failed"
             nil)]
          [:input.form-control
           {:type "text"
            ;;:value @token
            :placeholder "Input..."
            :on-change (fn [e]
                         (let [current-text (.. e -target -value)]
                           (dispatch [:set/user-token current-text])))
            :on-key-press (fn [e]
                            (when (== (.-charCode e) 13)
                              (search @token)))}]
          [:button.btn.btn-primary.btn-sm
           {:type "button"
            :id "search-button"
            :on-click (fn [e] (search @token))}
           "Search"]]
         (if @token-results
           [:div
            [:p (pr-str @token-results)]])]))))

;; ## Basic textarea box

(defn textarea-box []
  [:div.card
   [:div.card-header
    "作文欄"
    #_[:button.btn.btn-primary.btn-sm.pull-xs-right
       {:on-click
        (fn [e]
          (dispatch [:get/errors-register @user-text]))}
       [:i.material-icons "spellcheck"] #_" Error check"]]
   [:div.card-body
    [:textarea.form-control
     {:rows 5
      :placeholder "作文を入力してください..."
      :on-change (fn [e]
                   (let [text (.. e -target -value)]
                     (dispatch [:set/user-text text])))}]]])

(defn results-box
  [box-key]
  ;; (println box-key)
  (let [box-name (->>
                  (-> box-key
                      str
                      (str/replace ":" "")
                      (str/split #"/"))
                  (map str/capitalize)
                  (str/join " "))
        results (subscribe [box-key])]
    (fn []
      [:div.card
       [:div.card-header box-name]
       [:div.card-body
        [:p (pr-str @results)]]])))

;; ## Textual analysis

(defn text-analysis-box
  []
  (let [user-text (subscribe [:user/text])
        error-data (subscribe [:errors/register])]
    (fn []
      (let [errors (:results @error-data)
            tokens (:parsed-tokens @error-data)]
        [:div.card
         [:div.card-header "分析結果"]
         [:div.card-body
          (into [:ruby]
                (reduce
                 (fn [a {:keys [orth pos-1]}]
                   (conj a orth [:rt pos-1] " "))
                 []
                 tokens))
          [:br]
          (pr-str errors)]]))))

;; ## Draft.js editor integration

(comment
  (defn draftjs []
    (Draft/editor
     {:editorState @editor-state-atom
      :onChange    (fn [new-state]
                     (reset! editor-state-atom new-state)
                     (.forceUpdate @wrapper-state))})))

;; ## Collocation list w/ bar graph

(defn navbar []
  (let [connection-status (atom :online) #_(subscribe [:sente/connection-status])]
    (fn []
      [:nav {:class (case @connection-status
                      :online  "navbar navbar-fixed-top navbar-light bg-faded navbar-default"
                      :offline "navbar navbar-fixed-top navbar-light bg-faded navbar-danger"
                      nil)
             :role "navigation"}
       #_[:button.navbar-toggle {:type "button" :data-toggle "collapse"
                                 :data-target "#navbar-collapse"}
          [:span.sr-only "Toggle navigation"]
          [:span.icon-bar] [:span.icon-bar] [:span.icon-bar]]
       [:a.navbar-brand {:href "#"} "Cypress Editor"]

       [:ul.nav.navbar-nav
        [:li.nav-item.divider.pull-xs-right]

        [:li.nav-item.pull-xs-right
         [:a.nav-link
          [:i.material-icons
           (case @connection-status ;; TODO move to websocket status
             :online  "cloud"
             :loading "cloud_download"
             :offline "cloud_off"
             nil)]]]

        [:li.nav-item.pull-xs-right [:a.nav-link [:i.fa.fa-repeat] #_" Redo"]]
        [:li.nav-item.pull-xs-right [:a.nav-link [:i.fa.fa-undo] #_" Undo"]]

        #_[:li.active [:a {:href "#"} "Link"]]
        #_[:li.dropdown
           [:a.dropdown-toggle {:href "#" :data-toggle "dropdown"} "Profile" [:b.caret]]
           [:ul.dropdown-menu
            [:li [:a {:href "#"} "Action"]]
            [:li.divider]]]]
       #_[:div.collapse.navbar-toggleable-xs {:id "navbar-collapse"}
          ]])))

(defn footer []
  (let [connection-status (atom :online) #_(subscribe [:sente/connection-status])]
    (fn []
      [:nav {:class (case @connection-status
                      :online  "navbar bg-faded navbar-fixed-bottom navbar-default"
                      :offline "navbar bg-faded navbar-fixed-bottom navbar-danger"
                      nil)}
       [:div.navbar-header
        [:a.navbar-brand {:href "#"} "Stats"]
        [:span.navbar-brand (gstring/format "Words: %d, Sentences: %d, Paragraphs: %d"
                                            0 0 0)]]])))

(defn interface []
  [:div.container.is-fluid
   [navbar]
   [:div.row.editor-interface
    [:div.col-xs-6
     [textarea-box]
     #_[results-box :topics/infer]
     [results-box :errors/register]
     [text-analysis-box]]
    [:div.col-xs-6
     [input-box]
     #_[topic-model-box]
     [results-box :sentences/collocations]
     [results-box :sentences/tokens]
     [results-box :tokens/tree]
     [results-box :tokens/similarity]
     [results-box :tokens/nearest-tokens]
     [results-box :tokens/similarity-with-accuracy]
     [results-box :tokens/tsne]
     [results-box :collocations/collocations]
     [results-box :collocations/tree]]]
   [footer]])
