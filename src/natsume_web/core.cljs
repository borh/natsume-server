(ns natsume-web.core
  (:require
   ;; Do not remove the following requires needed by re-frame:
   [natsume-web.subs]
   [natsume-web.events]
   ;;

   [goog.dom :as dom]

   [taoensso.timbre]

   [natsume-web.routes :as routes]
   [natsume-web.fulltext-views :as rcv]
   [natsume-web.views :as views :refer [interface]]
   [natsume-web.config :refer [debug-enabled?]]
   [re-frisk.core :refer [enable-re-frisk!]]
   [clojure.spec.alpha :as s]
   [reagent.core :as r]
   [re-frame.core :refer [dispatch-sync clear-subscription-cache!]]
   [re-learn.core :as re-learn]
   #_[re-learn.views :as re-learn-views]))

;; # App entry point

(defn mount-root
  []
  #_(routes/app-routes)                                       ;; FIXME needed?
  (clear-subscription-cache!)
  (cond

    (dom/getElement "login")
    (r/render [rcv/login-box]
              (. js/document (getElementById "login")))

    (dom/getElement "app")
    (r/render [rcv/interface]
              (. js/document (getElementById "app")))

    (dom/getElement "natsume")
    (r/render [views/interface]
              (. js/document (getElementById "natsume")))

    #_(r/render [re-learn-views/tutorial-view {:context? true}]
                (. js/document (getElementById "learn")))))

(defn init []
  (routes/app-routes)
  (dispatch-sync [:boot])
  (when debug-enabled?
    (enable-console-print!)
    (println "Debug mode enabled...")
    (s/check-asserts true)
    (enable-re-frisk! {:x 50 :y 120}))
  (mount-root))

;; This is called every time you make a code change
(defn ^:dev/after-load reload []
  (mount-root))

(defonce run (init))
