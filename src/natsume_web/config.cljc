(ns natsume-web.config
  #?(:clj (:require [mount.core :as mount]
                    [natsume-server.config])))

#?(:cljs (def ^boolean debug-enabled? "@define {boolean}" ^boolean js/goog.DEBUG))

(comment
  #?(:cljs (goog-define ^boolean production? false))
  #?(:cljs (goog-define production-host "gpgpu.lang.osaka-u.ac.jp/natsume-search"))
  #?(:clj (mount/start #'natsume-server.config/config))
  #?(:clj (def production? (not (:dev natsume-server.config/config))))
  #?(:clj (def production-host (get-in natsume-server.config/config [:http :server-address])))

  #_(def base-url "nlp.lang.osaka-u.ac.jp/natsume-server/api")
  (def base-url (if production? production-host "localhost:3000"))
  (def api-host (str base-url "/api") #_"nlp.lang.osaka-u.ac.jp/natsume-server/api")

  (def api-protocol (if production? :https :http)))

;; As API is colocated with frontend, we provide relative paths.
(def api-url "api" #_(str (name api-protocol) "://" api-host))

(def files-url "cache/files/" #_(str (name api-protocol) "://" base-url "/cache/files"))

#_(def api-url
    (str "http://" base-url))

#_(def ws-url
    (str "ws://" base-url))
