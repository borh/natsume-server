(defproject natsume-server "1.0.0-SNAPSHOT"
  :description "Natsume writing assistance system data processor and API server"
  :url "http://hinoki.ryu.titech.ac.jp/natsume/"
  :scm {:url "https://github.com/borh/natsume-server.git"
        :name "git"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :repositories {"sonatype-oss-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"}
  :jvm-opts ^:replace ["-server" "-XX:+UseG1GC"]
  :dependencies [[org.clojure/clojure "1.7.0"]

                 [com.stuartsierra/component "0.3.0"]
                 [potemkin "0.4.1"]
                 [environ "1.0.1"]
                 [meta-merge "0.1.1"]
                 [prismatic/schema "1.0.3"]

                 [org.immutant/web "2.1.0" :exclusions [ring/ring-core]]
                 [io.pedestal/pedestal.service "0.4.1" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [io.pedestal/pedestal.service-tools "0.4.1" :exclusions [org.slf4j/log4j-over-slf4j]]
                 [io.pedestal/pedestal.immutant "0.4.1" :exclusions [org.immutant/web]]
                 [metosin/ring-swagger "0.22.0" :exclusions [metosin/ring-swagger-ui]]
                 [metosin/ring-swagger-ui "2.1.3-2"]
                 [frankiesardo/pedestal-swagger "0.4.4"]
                 [cheshire "5.5.0"]

                 ;; Database
                 [org.postgresql/postgresql "9.4-1205-jdbc42"] ; https://github.com/kdubb/pgjdbc-ng
                 [org.clojure/java.jdbc "0.4.2"]
                 [java-jdbc/dsl "0.1.3"]
                 [com.alibaba/druid "1.0.16"]
                 [honeysql "0.6.2"]
                 ;;[yesql "0.4.0" :exclusions [instaparse]]
                 ;;

                 ;; Utils
                 [org.tukaani/xz "1.5"]
                 [org.apache.commons/commons-compress "1.10"]
                 [org.clojure/tools.reader "1.0.0-alpha1"]
                 [com.taoensso/timbre "4.1.4"]
                 [org.clojure/tools.cli "0.3.3"]
                 [me.raynes/fs "1.4.6"]
                 [iota "1.1.3"]
                 [org.clojure/data.csv "0.1.3"]
                 [dk.ative/docjure "1.9.0"]
                 [fast-zip "0.6.1" :exclusions [com.cemerick/austin]]
                 [automat "0.2.0-alpha2"]
                 [org.apache.commons/commons-math3 "3.5"]
                 [fipp "0.6.3"]
                 [org.flatland/ordered "1.5.3"] ;; For ordered routes in Swagger
                 [camel-snake-kebab "0.3.2" :exclusions [org.clojure/clojure]]
                 ;;

                 ;; Stats/models/ML
                 [net.mikera/core.matrix "0.44.0"]
                 [incanter/incanter-core "1.9.0" :exclusions [net.mikera/core.matrix]]
                 ;; [com.aliasi/lingpipe "4.1.0"] ;; TODO
                 [bigml/sampling "3.0" :exclusions [incanter/parallelcolt]]
                 [prismatic/plumbing "0.5.1" :exclusions [fs potemkin prismatic/schema]]
                 ;; [prismatic/hiphip "0.1.0"] ;; TODO
                 [cc.qbits/knit "0.3.0"]
                 [org.clojure/core.incubator "0.1.3"]
                 [org.clojure/core.match "0.3.0-alpha5-SNAPSHOT"]
                 ;;;; word2vec/doc2vec TODO:
                 ;;[org.nd4j/nd4j-api "0.0.3.5.5.2" :exclusions [org.slf4j/slf4j-api commons-io]]
                 ;;[org.nd4j/nd4j-jblas "0.0.3.5.5.2"]
                 ;;[org.deeplearning4j/deeplearning4j-core "0.0.3.3.2.alpha1" :exclusions [org.nd4j/nd4j-api commons-io com.fasterxml.jackson.core/jackson-databind]]
                 ;;[org.deeplearning4j/deeplearning4j-scaleout-akka "0.0.3.3.2.alpha1" :exclusions [org.slf4j/slf4j-api commons-io]]
                 ;;[org.deeplearning4j/deeplearning4j-nlp "0.0.3.3.2.alpha1" :exclusions [org.slf4j/slf4j-api commons-io]]
                 ;;

                 ;; Text processing
                 [org.chasen/cabocha "0.69"]
                 [com.ibm.icu/icu4j "56.1"]
                 [d3-compat-tree "0.0.8"]
                 ;;

                 ]
  :plugins [[lein-environ "1.0.1"]]
  :duct {:ns-prefix natsume-server}
  :main ^:skip-aot natsume-server.main
  :profiles
  {:dev  [:project/dev  :profiles/dev]
   :test [:project/test :profiles/test]
   :uberjar {:aot :all :resource-paths ["swagger-ui"]}
   :profiles/dev  {}
   :profiles/test {}
   :project/dev   {:source-paths ["dev"]
                   :repl-options {:init-ns user}
                   :dependencies [[reloaded.repl "0.2.1"]
                                  [org.clojure/tools.namespace "0.3.0-alpha1"]]
                   :env {:db {:subname "//localhost:5432/natsumedev"
                              :user "natsumedev"
                              :password "riDJMq98LpyWgB7F"}
                         :http {:port 3000}
                         :log {:directory "./log"}
                         :dirs ["/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OT"]
                         :verbose false
                         :clean true
                         :process true
                         :search true
                         :sampling {:ratio 0.0
                                    :seed 2
                                    :replace false
                                    :hold-out false}}}
   :project/test  {}})
