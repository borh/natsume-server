(defproject natsume-server "0.3.0-SNAPSHOT"
  :min-lein-version "2.0.0"
  :description "Natsume writing assistance system data processor and server"
  :url "http://hinoki.ryu.titech.ac.jp/natsume/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ["-server" "-XX:+UseG1GC" "-XX:+CMSParallelRemarkEnabled" "-XX:+AggressiveOpts" "-XX:+UseFastAccessorMethods" "-XX:+UseCompressedOops" "-XX:+DoEscapeAnalysis" "-XX:+UseBiasedLocking" "-XX:PermSize=64M" "-XX:MaxPermSize=512M" "-Xmx2g"] ; For profiling: "-Xshare:off"
  :main ^{:skip-aot true} natsume-server.core
  :dependencies [[org.clojure/clojure "1.5.1"]

                 ;; Database
                 [postgresql #_"9.1-901-1.jdbc4" "9.2-1002.jdbc4"]
                 [org.clojure/java.jdbc "0.2.3" #_"0.3.0-alpha1"]
                 [log4j "1.2.17" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]
                 [korma "0.3.0-RC5"]
                 ;;

                 ;; TODO: https://github.com/jkk/honeysql

                 ;; Logging
                 [ch.qos.logback/logback-classic "1.0.12" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.5"]
                 [org.slf4j/jcl-over-slf4j "1.7.5"]
                 [org.slf4j/log4j-over-slf4j "1.7.5"]
                 ;;

                 ;; Fulltext search
                 [org.elasticsearch/elasticsearch "0.90.0"]
                 [clojurewerkz/elastisch "1.1.0-rc2"]
                 ;;

                 ;; Utils
                 [org.apache.commons/commons-compress "1.5"]
                 [org.clojure/tools.reader "0.7.4"]
                 [com.taoensso/timbre "1.6.0"]
                 [clj-configurator "0.1.3"]
                 [org.dave/cfg "1.0.0"]
                 [org.clojure/tools.cli "0.2.2"]
                 [me.raynes/fs "1.4.2"]
                 [iota "1.0.3"]
                 [org.clojure/data.csv "0.1.2"]
                 ;;

                 ;; Natane (refactor out)
                 [me.raynes/conch "0.5.1"]
                 ;;

                 ;; Webserver related
                 [io.pedestal/pedestal.service "0.1.6"]
                 [io.pedestal/pedestal.jetty "0.1.6"]
                 ;; [io.pedestal/pedestal.tomcat "0.1.3"]
                 [cheshire "5.1.1"]
                 ;;[org.blancas/kern "0.7.0"]
                 [camel-snake-kebab "0.1.0"]
                 ;; Authentication TODO: friend & https://github.com/osbert/persona-kit
                 ;;

                 ;; ClojureScript
                 ;;

                 ;; TODO: https://github.com/bagucode/clj-native for better C bindings
                 ;;       Also: http://code.google.com/p/jnaerator/

                 ;; Stats/models/ML
                 [incanter "1.5.0-SNAPSHOT" :exclusions [junit org.clojure/core.incubator slingshot]]
                 [com.aliasi/lingpipe "4.1.0"]
                 ;; [clj-liblinear "0.0.1-SNAPSHOT"] ; TODO https://github.com/lynaghk/clj-liblinear
                 [bigml/sampling "2.1.0"]
                 [prismatic/plumbing "0.1.0"]
                 [cc.qbits/knit "0.2.1"]
                 ;;[org.clojure/core.match "0.2.0-alpha12"] ; TODO
                 ;;[org.clojure/core.logic "0.8.3"] ; TODO
                 ;;[pldb "0.1.1"]
                 ;;[readyforzero/babbage "1.0.2"] ; TODO
                 ;;

                 ;; Text processing
                 [org.chasen/cabocha "0.66"]
                 [com.ibm.icu/icu4j "50.1.1"]
                 ;;
                 ]
  :plugins [[speclj "2.5.0"]
            ;;[lein-ring "0.8.3"]
            [lein-cljsbuild "0.3.0" :exclusions [org.clojure/clojurescript org.clojure/google-closure-library-third-party]]]
  :test-paths ["spec/"]
  ;;:ring {:handler natsume-server.server/handler}
  :resources-paths ["config" "public"]
  :source-paths ["src" "src/cljs" "public"]
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
                                  [peridot "0.1.0" :exclusions [org.apache.httpcomponents/httpmime]] ; TODO
                                  [fipp "0.1.0-SNAPSHOT"]]
                   ;;:jvm-opts ["-server" "-XX:+UseG1GC" "-XX:+CMSParallelRemarkEnabled" "-XX:+AggressiveOpts" "-XX:+UseFastAccessorMethods" "-XX:+UseCompressedOops" "-XX:+DoEscapeAnalysis" "-XX:+UseBiasedLocking" "-XX:PermSize=64M" "-XX:MaxPermSize=256M" "-Xmx2g" "-XX:+TieredCompilation" "-XX:TieredStopAtLevel=3"]
                   :source-paths ["dev"]}
             :production {:ring {:open-browser? false
                                 :stacktraces?  false
                                 :auto-reload?  false}}}
  :pedantic :warn)
