(defproject natsume-clj "0.3.0-SNAPSHOT"
  :min-lein-version "2.0.0"
  :description "Natsume writing assistance system data processor and server"
  :url "http://hinoki.ryu.titech.ac.jp/natsume/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ["-server" "-XX:+UseG1GC" "-XX:+CMSParallelRemarkEnabled" "-XX:+AggressiveOpts" "-XX:+UseFastAccessorMethods" "-XX:+UseCompressedOops" "-XX:+DoEscapeAnalysis" "-XX:+UseBiasedLocking" "-XX:PermSize=64M" "-XX:MaxPermSize=256M" "-Xmx2g"] ; For profiling: "-Xshare:off"
  :main natsume-server.core
  :dependencies [[org.clojure/clojure "1.5.1"]

                 ;; Database
                 [postgresql #_"9.1-901-1.jdbc4" "9.2-1002.jdbc4"]
                 [org.clojure/java.jdbc "0.2.3"]
                 [log4j "1.2.17" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]
                 [korma "0.3.0-RC5"]
                 ;;

                 ;; Fulltext search
                 ;; native client:
                 [clj-elasticsearch "0.4.0-RC1"]
                 [org.elasticsearch/elasticsearch "0.90.0.RC1"]
                 ;; or REST client:
                 [clojurewerkz/elastisch "1.1.0-beta2"]
                 ;;

                 ;; Utils
                 [org.apache.commons/commons-compress "1.5"]
                 [org.clojure/tools.reader "0.7.3"]
                 [com.taoensso/timbre "1.5.2"]
                 [clj-configurator "0.1.3"]
                 [org.clojure/tools.cli "0.2.2"]
                 [me.raynes/fs "1.4.1"]
                 [iota "1.0.3"]
                 [org.clojure/data.csv "0.1.2"]
                 ;;

                 ;; Natane (refactor out)
                 [me.raynes/conch "0.5.1"]
                 [org.clojure/data.xml "0.0.7"]
                 ;;

                 ;; Webserver related
                 [ring "1.1.8" :exclusions [clj-time]]
                 [ring-cors "0.1.0"]
                 [compojure "1.1.5" :exclusions [org.clojure/tools.macro #_org.clojure/core.incubator clj-time ring/ring-core]]
                 [cheshire "5.0.2"]
                 ;;

                 ;; ClojureScript
                 [com.keminglabs/c2 "0.2.2"]
                 [cljsbuild "0.3.0" :exclusions [org.clojure/google-closure-library-third-party org.clojure/clojurescript]]
                 ;;

                 ;; Stats/models/ML
                 [incanter "1.5.0-SNAPSHOT" :exclusions [junit org.clojure/core.incubator slingshot]]
                 [com.aliasi/lingpipe "4.1.0"]
                 [bigml/sampling "2.1.0"]
                 [prismatic/plumbing "0.0.1"]
                 ;;[org.clojure/core.match "0.2.0-alpha12"] ; TODO
                 ;;[org.clojure/core.logic "0.8.1"] ; TODO
                 ;;[pldb "0.1.1"]
                 ;;[readyforzero/babbage "1.0.1"] ; TODO
                 ;;

                 ;; Text processing
                 [org.chasen/cabocha "0.66"]
                 [com.ibm.icu/icu4j "50.1.1"]
                 ;;
                 ]
  :plugins [[speclj "2.5.0"]
            [lein-ring "0.8.3"]
            [lein-cljsbuild "0.3.0" :exclusions [org.clojure/clojurescript org.clojure/google-closure-library-third-party]]]
  :test-paths ["spec/"]
  :ring {:handler natsume-server.server/handler}
  :resources-path "public"
  :source-paths ["src" "src/cljs"]
  ;;:hooks [leiningen.cljsbuild]
  :cljsbuild {:builds
              [{:source-paths ["src/cljs"]
                :jar true
                :compiler {:pretty-print false
                           :output-to "public/natsume.js"
                           :optimizations :advanced}}]}
  :profiles {:dev {:dependencies [[speclj "2.5.0"]
                                  [criterium "0.3.1"]
                                  [ring-mock "0.1.3"]
                                  [fipp "0.1.0-SNAPSHOT"]]}
             :production {:ring {:open-browser? false
                                 :stacktraces?  false
                                 :auto-reload?  false}}}
  :pedantic :warn)
