(defproject natsume-server "0.4.0-SNAPSHOT"
  :description "Natsume writing assistance system data processor and API server"
  :url "http://hinoki.ryu.titech.ac.jp/natsume/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ["-server" "-XX:+UseG1GC"]
  :main ^{:skip-aot true} natsume-server.core
  :scm {:url "https://github.com/borh/natsume-server.git"
        :name "git"}
  :dependencies [[org.clojure/clojure "1.6.0"]

                 ;; Database
                 [org.postgresql/postgresql "9.2-1003-jdbc4"] ; https://github.com/kdubb/pgjdbc-ng
                 [org.clojure/java.jdbc "0.3.0"]
                 [java-jdbc/dsl "0.1.0"]
                 [com.alibaba/druid "1.0.7"]
                 [honeysql "0.4.3"]
                 ;;

                 ;; Logging (for Pedestal)
                 [ch.qos.logback/logback-classic "1.1.2" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/slf4j-api "1.7.7"]
                 [org.slf4j/jul-to-slf4j "1.7.7"]
                 [org.slf4j/jcl-over-slf4j "1.7.7"]
                 [org.slf4j/log4j-over-slf4j "1.7.7"]
                 ;;

                 ;;;; Fulltext search
                 ;;[org.elasticsearch/elasticsearch "1.0.0.Beta2"]
                 ;;[clojurewerkz/elastisch "1.4.0"]
                 ;;;;

                 ;; Utils
                 [org.apache.commons/commons-compress "1.6"]
                 [org.clojure/tools.reader "0.8.5"]
                 [com.taoensso/timbre "3.2.1"]
                 [clj-configurator "0.1.5"]
                 [org.dave/cfg "1.0.0"]
                 [org.clojure/tools.cli "0.3.1"]
                 [me.raynes/fs "1.4.6"]
                 [iota "1.1.2"]
                 [org.clojure/data.csv "0.1.2"]
                 [org.flatland/useful "0.11.2"]
                 [fast-zip "0.4.0"]
                 [org.apache.commons/commons-math3 "3.3"]
                 [fipp "0.4.3"]
                 ;;

                 ;; Natane (refactor out)
                 [me.raynes/conch "0.7.0"]
                 ;;

                 ;; Webserver related
                 ;;[http-kit "2.1.4"]
                 ;;[ring/ring-core "1.2.0-RC1"]
                 ;;[compojure "1.2.0-SNAPSHOT"]
                 ;; TODO ZeroMQ: http://augustl.com/blog/2013/zeromq_instead_of_http/
                 [io.pedestal/pedestal.service "0.2.2"]
                 [io.pedestal/pedestal.service-tools "0.2.2"]
                 [io.pedestal/pedestal.jetty "0.2.2"]
                 ;; [io.pedestal/pedestal.tomcat "0.2.1"]
                 [cheshire "5.3.1"]
                 ;;[org.blancas/kern "0.7.0"]
                 [camel-snake-kebab "0.2.1"]
                 [com.novemberain/validateur "2.2.0"]
                 [org.clojure/core.cache "0.6.3"]
                 ;; Authentication TODO: friend & https://github.com/osbert/persona-kit
                 [org.clojure/clojurescript "0.0-2280"]
                 [om "0.7.1"]
                 ;;

                 ;; ClojureScript
                 ;;

                 ;; TODO: https://github.com/bagucode/clj-native for better C bindings
                 ;;       Also: http://code.google.com/p/jnaerator/

                 ;; Stats/models/ML
                 [incanter "1.5.5"]
                 ;;[org.clojure/math.numeric-tower "0.0.2"]
                 [com.aliasi/lingpipe "4.1.0"]
                 ;; [clj-liblinear "0.0.1-SNAPSHOT"] ; TODO https://github.com/lynaghk/clj-liblinear
                 [bigml/sampling "2.1.1"]
                 [prismatic/plumbing "0.3.3"]
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
                 [com.ibm.icu/icu4j "53.1"]
                 [d3-compat-tree "0.0.3"]
                 ;;
                 ]
  :min-lein-version "2.0.0"
  :test-paths ["spec/"]
  :plugins [[speclj "2.9.1"] [lein-cljsbuild "0.3.3"]]
  :resources-paths ["config" "public"]
  :source-paths ["src" "public"]
  :aliases {"run-dev" ["trampoline" "run" "-m" "natsume-server.api.service/run-dev"]}
  ;;:hooks [leiningen.cljsbuild]
  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src/cljs"]
                        :compiler {:output-to "public/main.js"
                                   :output-dir "public/out"
                                   :optimizations :none
                                   :source-map true}}
                       {:id "release"
                        :source-paths ["src/cljs"]
                        :compiler {:output-to "public/main.js"
                                   :optimizations :advanced
                                   :pretty-print false
                                   :preamble ["react/react.min.js"]
                                   :externs ["react/externs/react.js"]}}]}
  #_:repl-options  #_{:init-ns user
                  :init (try
                          (use 'io.pedestal.service-tools.dev)
                          (require 'natsume-server.api.service)
                          ;; Nasty trick to get around being unable to reference non-clojure.core symbols in :init
                          (eval '(init natsume-server.api.service/service #'natsume-server.api.service/routes))
                          (catch Throwable t
                            (println "ERROR: There was a problem loading io.pedestal.service-tools.dev")
                            (clojure.stacktrace/print-stack-trace t)
                            (println)))
                  :welcome (println "Welcome to natsume-server! Run (tools-help) to see a list of useful functions.")}
  :profiles {:dev {:jvm-opts ["-server" "-XX:+UseG1GC" "-Xshare:off" "-XX:-OmitStackTraceInFastThrow"]
                   :plugins [[com.cemerick/austin "0.1.1"]]
                   :dependencies [[speclj "3.0.2"]
                                  [criterium "0.4.3"]
                                  [ring-mock "0.1.5"]
                                  [org.clojure/tools.namespace "0.2.5"]
                                  [org.clojure/java.classpath "0.2.2"]
                                  [peridot "0.3.0" :exclusions [org.apache.httpcomponents/httpmime]] ; TODO
                                  ]}
             :server     {:jvm-opts ["-server" "-XX:+UseG1GC" "-XX:+CMSParallelRemarkEnabled" "-XX:+AggressiveOpts" "-XX:+UseFastAccessorMethods" "-XX:+UseCompressedOops" "-XX:+DoEscapeAnalysis" "-XX:+UseBiasedLocking" "-XX:MaxGCPauseMillis=20"]} ;; FIXME benchmark
             :production {:jvm-opts ["-server" "-XX:+UseG1GC" "-XX:+CMSParallelRemarkEnabled" "-XX:+AggressiveOpts" "-XX:+UseFastAccessorMethods" "-XX:+UseCompressedOops" "-XX:+DoEscapeAnalysis" "-XX:+UseBiasedLocking" "-Xmx8g"]}}
  :pedantic :warn)
