(ns natsume-server.log-config)

;; ## Helper function to configure timbre log
(defn setup-log
  [config log-level]
  (swap! config assoc-in [:timestamp-pattern] "HH:mm:ss.SSS")
  (swap! config assoc-in [:prefix-fn]
         (fn [{:keys [level timestamp hostname ns]}]
           (str timestamp " " (name level) " [" ns "]")))
  (swap! config assoc-in [:appenders :standard-out :enabled?] false)
  (swap! config assoc-in [:appenders :spit-appender]
                 {:doc       "Natsume file appender"
                  :min-level log-level
                  :enabled?  true
                  :async?    true
                  :max-message-per-msecs nil
                  :fn (fn [{:keys [ap-config level prefix message more] :as args}]
                        (spit "natsume.log" (format "%s: %s\n" prefix message) :append true))}))
