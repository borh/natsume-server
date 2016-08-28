(ns natsume-server.config
  (:require [natsume-server.utils.naming :refer [underscores->dashes]]
            [mount.core :as mount :refer [defstate]]
            [environ.core :refer [env]]))

(let [default-config
      {:db       {:subname  "//localhost:5432/natsumedev"
                  :user     "natsumedev"
                  :password (or (env :db-password) "riDJMq98LpyWgB7F")}
       :http     {:port (or (some-> env :http-port Integer.) 3000)
                  :pretty-print? false
                  :server-address (or
                                   (some-> env :server-address)
                                   (format "%s:%s"
                                           (or (env :http-url) "http://localhost")
                                           (or (some-> env :http-port Integer.) 3000)))}
       :log      {:directory "./log"}
       :dirs     ["/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OW"
                  "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OL"
                  "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OT"
                  "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OP"
                  "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/PN"
                  "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/PM"
                  "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OV"
                  "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/PB"
                  "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/LB"
                  "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OB"
                  "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OC"
                  "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OY"
                  "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OM"

                  "/data/STJC/nlp_annual_txt"               ;; 309.25 min
                  "/data/STJC/JNLP-Journal"                 ;;  16.55 min
                  "/data/STJC/JStage-土木学会論文集A"
                  "/data/STJC/JStage-土木学会論文集B"
                  "/data/STJC/JStage-土木学会論文集C"
                  "/data/STJC/JStage-土木学会論文集D"
                  "/data/STJC/JStage-日本医科大学医学会雑誌"
                  "/data/STJC/JStage-電気学会論文誌"
                  "/data/STJC/JStage-日本化学会誌"
                  "/data/STJC/JStage-環境資源工学"]
       :verbose  false
       :clean    false
       :process  false
       :search   false
       :server   true
       :sampling {:ratio    0.0 ;; 0.01 => 22.09, 0.10 => 243.34
                  :seed     2
                  :replace  false
                  :hold-out false}
       :word2vec [{:unit-type :suw
                   :features [:orth]}
                  {:unit-type :suw
                   :features [:lemma]}
                  {:unit-type :unigrams
                   :features [:string]}]
       :topic-models [{:unit-type :suw
                       :features [:orth]}]}]
  (defstate run-mode :start (do (println (env :run-mode)) (or (some-> env :run-mode underscores->dashes) :dev-server)))
  (defstate config
    :start (do
             (println "Running in" (name run-mode) "mode...")
             (case run-mode
               :prod-load (merge default-config
                                 {:clean true
                                  :process true
                                  :search true
                                  :server false})
               :dev-load (-> default-config
                             (merge {:clean true
                                     :process true
                                     :search true
                                     :server false
                                     :verbose true})
                             (assoc-in [:sampling :ratio] 0.001))
               :prod-server default-config
               :dev-server  (assoc-in default-config [:http :pretty-print?] true)))))
