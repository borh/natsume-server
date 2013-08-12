(defproject natsume-server "0.4.0-SNAPSHOT"
  :min-lein-version "2.0.0"
  :description "Natsume writing assistance system data processor and API server"
  :url "http://hinoki.ryu.titech.ac.jp/natsume/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ["-server" "-XX:+UseG1GC"]
  :main ^{:skip-aot true} natsume-server.core
  :dependencies [[org.clojure/clojure "1.5.1"]

                 ;; Database
                 [org.postgresql/postgresql "9.2-1003-jdbc4"] ; https://github.com/kdubb/pgjdbc-ng
                 [org.clojure/java.jdbc "0.3.0-alpha4"]
                 [com.alibaba/druid "0.2.25"]
                 [honeysql "0.4.2"]
                 ;;

                 ;; Logging (for Pedestal)
                 [ch.qos.logback/logback-classic "1.0.13" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/slf4j-api "1.7.5"]
                 [org.slf4j/jul-to-slf4j "1.7.5"]
                 [org.slf4j/jcl-over-slf4j "1.7.5"]
                 [org.slf4j/log4j-over-slf4j "1.7.5"]
                 ;;

                 ;; Fulltext search
                 [org.elasticsearch/elasticsearch "0.90.3"]
                 [clojurewerkz/elastisch "1.2.0"]
                 ;;

                 ;; Utils
                 [org.apache.commons/commons-compress "1.5"]
                 [org.clojure/tools.reader "0.7.5"]
                 [com.taoensso/timbre "2.5.0"]
                 [clj-configurator "0.1.3"]
                 [org.dave/cfg "1.0.0"]
                 [org.clojure/tools.cli "0.2.4"]
                 [me.raynes/fs "1.4.5"]
                 [iota "1.1.1"]
                 [org.clojure/data.csv "0.1.2"]
                 [org.flatland/useful "0.10.3"]
                 ;;

                 ;; Natane (refactor out)
                 [me.raynes/conch "0.5.1"]
                 ;;

                 ;; Webserver related
                 ;;[http-kit "2.1.4"]
                 ;;[ring/ring-core "1.2.0-RC1"]
                 ;;[compojure "1.2.0-SNAPSHOT"]
                 ;; TODO ZeroMQ: http://augustl.com/blog/2013/zeromq_instead_of_http/
                 [io.pedestal/pedestal.service "0.1.10"]
                 [io.pedestal/pedestal.jetty "0.1.10"]
                 ;; [io.pedestal/pedestal.tomcat "0.1.10"]
                 [cheshire "5.2.0"]
                 ;;[org.blancas/kern "0.7.0"]
                 [camel-snake-kebab "0.1.1"]
                 [com.novemberain/validateur "1.5.0"]
                 [org.clojure/core.cache "0.6.3"]
                 ;; Authentication TODO: friend & https://github.com/osbert/persona-kit
                 ;;

                 ;; ClojureScript
                 ;;

                 ;; TODO: https://github.com/bagucode/clj-native for better C bindings
                 ;;       Also: http://code.google.com/p/jnaerator/

                 ;; Stats/models/ML
                 [incanter "1.5.2"]
                 ;;[org.clojure/math.numeric-tower "0.0.2"]
                 [com.aliasi/lingpipe "4.1.0"]
                 ;; [clj-liblinear "0.0.1-SNAPSHOT"] ; TODO https://github.com/lynaghk/clj-liblinear
                 [bigml/sampling "2.1.0"]
                 [prismatic/plumbing "0.1.0"]
                 ;; [prismatic/hiphip "0.1.0"] ;; TODO
                 [cc.qbits/knit "0.2.1"]
                 [org.clojure/core.incubator "0.1.3"]
                 ;;[org.clojure/core.match "0.2.0-alpha12"] ; TODO
                 ;;[org.clojure/core.logic "0.8.3"] ; TODO
                 ;;[pldb "0.1.1"]
                 ;;[readyforzero/babbage "1.0.2"] ; TODO
                 ;;

                 ;; Text processing
                 [org.chasen/cabocha "0.66"]
                 [com.ibm.icu/icu4j "51.2"]
                 ;;
                 ]
  :plugins [[lein-cljsbuild "0.3.2"]]
  :test-paths ["spec/"]
  :resources-paths ["config" "public"]
  :source-paths ["src" "src/cljs" "public"]
  ;;:hooks [leiningen.cljsbuild]
  :cljsbuild {:builds
              [{:source-paths ["src/cljs"]
                :jar true
                :compiler {:pretty-print false
                           :output-to "public/natsume.js"
                           :source-map "public/natsume.js.map"
                           :optimizations :advanced}}]}
  :profiles {:dev {:jvm-opts ["-server" "-XX:+UseG1GC" "-Xshare:off"]
                   :plugins [[com.cemerick/austin "0.1.0"]]
                   :dependencies [[speclj "2.7.4"]
                                  [criterium "0.4.1"]
                                  [ring-mock "0.1.5"]
                                  [org.clojure/tools.namespace "0.2.4"]
                                  [org.clojure/java.classpath "0.2.1"]
                                  [peridot "0.2.2" :exclusions [org.apache.httpcomponents/httpmime]] ; TODO
                                  ]
                   :source-paths ["dev"]}
             :server     {:jvm-opts ["-server" "-XX:+UseG1GC" "-XX:+CMSParallelRemarkEnabled" "-XX:+AggressiveOpts" "-XX:+UseFastAccessorMethods" "-XX:+UseCompressedOops" "-XX:+DoEscapeAnalysis" "-XX:+UseBiasedLocking" "-XX:MaxGCPauseMillis=20"]} ;; FIXME benchmark
             :production {:jvm-opts ["-server" "-XX:+UseG1GC" "-XX:+CMSParallelRemarkEnabled" "-XX:+AggressiveOpts" "-XX:+UseFastAccessorMethods" "-XX:+UseCompressedOops" "-XX:+DoEscapeAnalysis" "-XX:+UseBiasedLocking" "-XX:PermSize=64M" "-XX:MaxPermSize=512M" "-Xmx4g"]}}
  :pedantic :warn)
