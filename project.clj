(defproject natsume-server "1.0.0-SNAPSHOT"
  :description "Natsume writing assistance system data processor and API server"
  :url "http://hinoki.ryu.titech.ac.jp/natsume/"
  :scm {:url "https://github.com/borh/natsume-server.git"
        :name "git"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :repositories {"sonatype-oss-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"}
  :jvm-opts ["-server" "-XX:+UseG1GC"]
  :dependencies [[org.clojure/clojure "1.7.0-RC1"]       ;; alpha5 >> core.matrix.dataset compile error?!

                 [com.stuartsierra/component "0.2.3"]
                 [potemkin "0.3.13"]
                 [duct "0.1.1"]
                 [environ "1.0.0"]
                 [meta-merge "0.1.1"]
                 [prismatic/schema "0.4.2"]

                 ;; TODO
                 [org.immutant/web "2.0.1"]
                 [instaparse "1.4.0"]                       ;; FIXME Override
                 [io.pedestal/pedestal.service "0.4.0" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [io.pedestal/pedestal.service-tools "0.4.0"]
                 [io.pedestal/pedestal.immutant "0.4.0" :exclusions [org.immutant/web]]
                 [metosin/ring-swagger "0.20.3" :exclusions [metosin/ring-swagger-ui]]
                 [metosin/ring-swagger-ui "2.1.5-M2"]
                 [frankiesardo/pedestal-swagger "0.4.0"]
                 [cheshire "5.4.0"]

                 ;; Database
                 [org.postgresql/postgresql "9.4-1201-jdbc41"] ; https://github.com/kdubb/pgjdbc-ng
                 [org.clojure/java.jdbc "0.3.7"]
                 [java-jdbc/dsl "0.1.2"]
                 [com.alibaba/druid "1.0.14"]
                 [honeysql "0.6.0"]
                 ;;[yesql "0.4.0" :exclusions [instaparse]]
                 ;;

                 ;; Utils
                 [org.tukaani/xz "1.5"]
                 [org.apache.commons/commons-compress "1.9"]
                 [org.clojure/tools.reader "0.9.2"]
                 [com.taoensso/timbre "3.4.0"]
                 [org.clojure/tools.cli "0.3.1"]
                 [me.raynes/fs "1.4.6"]
                 [iota "1.1.2"]
                 [org.clojure/data.csv "0.1.2"]
                 [dk.ative/docjure "1.8.0"]
                 [fast-zip "0.6.1"]
                 [org.apache.commons/commons-math3 "3.5"]
                 [fipp "0.6.2"]
                 [camel-snake-kebab "0.3.1" :exclusions [org.clojure/clojure]]
                 ;;

                 ;; Stats/models/ML
                 [net.mikera/core.matrix "0.34.0"]
                 [incanter/incanter-core "1.9.0" :exclusions [net.mikera/core.matrix]]
                 ;; [com.aliasi/lingpipe "4.1.0"] ;; TODO
                 [bigml/sampling "3.0"]
                 [prismatic/plumbing "0.4.3" :exclusions [fs potemkin prismatic/schema]]
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
                 [org.chasen/cabocha "0.68"]
                 [com.ibm.icu/icu4j "55.1"]
                 [d3-compat-tree "0.0.4"]
                 ;;

                 ]
  :plugins [[lein-environ "1.0.0"]
            [lein-gen "0.2.2"]]
  :generators [[duct/generators "0.1.0"]]
  :duct {:ns-prefix natsume-server}
  :main ^:skip-aot natsume-server.main
  :aliases {"gen" ["generate"]
            "setup" ["do" ["generate" "locals"]]}
  :profiles
  {:dev  [:project/dev  :profiles/dev]
   :test [:project/test :profiles/test]
   :uberjar {:aot :all :resource-paths ["swagger-ui"]}
   :profiles/dev  {}
   :profiles/test {}
   :project/dev   {:source-paths ["dev"]
                   :repl-options {:init-ns user}
                   :dependencies [[reloaded.repl "0.1.0"]
                                  [org.clojure/tools.namespace "0.2.10"]
                                  [kerodon "0.6.1" :exclusions [org.flatland/ordered]]]
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
