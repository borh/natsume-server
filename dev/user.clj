(ns user
  (:require [clojure.repl :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.java.io :as io]
            [environ.core :refer [env]]
            [meta-merge.core :refer [meta-merge]]
            [natsume-server.config :as config]
            [reloaded.repl :refer [system init start stop go reset]]
            [natsume-server.system :as system]
            [natsume-server.nlp.evaluation :as e]))

(def config
  (meta-merge
    config/defaults
    config/environ
    {:db       {:subname  "//localhost:5432/natsumedev"
                :user     "natsumedev"
                :password "riDJMq98LpyWgB7F"}
     :http     {:port 3000
                :server-address
                #_"https://wombat.hinoki-project.org/natsume-server"
                "http://localhost:3000"}
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
                "/data/STJC/JStage-環境資源工学"
                ]
     :verbose  true
     :clean    false #_true
     :process  false #_true
     :search   false #_true
     :server   true  #_false ;; TODO
     :sampling {:ratio    0.001 ;; 0.01 => 22.09, 0.10 => 243.34
                :seed     2
                :replace  false
                :hold-out false}}))

;; (when (io/resource "local.clj")
;;   (load "local"))

(reloaded.repl/set-init! #(system/new-system config))
