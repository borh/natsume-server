(ns natsume-web.communication
  (:require [taoensso.sente :as sente]
            [taoensso.sente.packers.transit :as sente-transit]
            [re-frame.core :refer [dispatch]]
            [natsume-web.config :refer [debug-enabled? api-protocol api-host]]))

;; https://github.com/ptaoussanis/sente/issues/118#issuecomment-87378277

(def !socket (atom false))
(def !router (atom false))

(defn send! [& params]
  (apply (:send-fn @!socket) params))

(defmulti handle-message first)

(defmulti handle-event :id)

(defmethod handle-event :chsk/state [{:keys [state]}]
  (dispatch [:set/sente-connection-status (:open? @state)]))

(defmethod handle-event :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token-deprecated ?handshake-data] ?data]
    (when debug-enabled?
      (println "Handshake: %s" ?data))))

(defmethod handle-event :chsk/recv [{:keys [?data ?csrf-token]}]
  (handle-message ?data))

(defmethod handle-message :chsk/ws-ping [_]
  (dispatch [:set/sente-connection-status (:open? @(:state @!socket))]))

(defn create-socket! [token]
  (reset! !socket
          (sente/make-channel-socket!
           "/chsk"
           js/csrfToken
           {:type :auto
            :packer (sente-transit/get-transit-packer)
            :client-id token
            :params {:uid "uuid"}
            ;; :ajax-opts {:with-credentials? true
            ;;             :headers {:X-CSRF-Token js/csrfToken}}
            ;;:chsk-url-fn #(str ws-url %)
            :protocol api-protocol
            :host api-host}))
  (reset! !router (sente/start-chsk-router! (:ch-recv @!socket) handle-event)))

(defn reconnect! []
  (when debug-enabled?
    (println "reconnecting"))
  (when @!socket
    (sente/chsk-reconnect! @!socket)))
