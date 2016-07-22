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
  :dependencies [[org.clojure/clojure "1.9.0-alpha10"]

                 [mount "0.1.10"]
                 [potemkin "0.4.3"]
                 [environ "1.0.1"]
                 ;;[meta-merge "0.1.1"]
                 [prismatic/schema "1.1.2"]

                 ;; Web
                 [cheshire "5.6.3"]
                 [yada "1.1.29"]
                 [aleph "0.4.1"]

                 ;; Database
                 [org.postgresql/postgresql "9.4.1209"] ; https://github.com/kdubb/pgjdbc-ng
                 [org.clojure/java.jdbc "0.6.1"]
                 [java-jdbc/dsl "0.1.3"]
                 [com.alibaba/druid "1.0.23"]
                 [honeysql "0.7.0"]
                 ;;[com.layerware/hugsql "0.4.7"]
                 ;;

                 ;; Utils
                 [org.tukaani/xz "1.5"]
                 [org.apache.commons/commons-compress "1.12"]
                 [org.clojure/tools.reader "1.0.0-alpha1"]
                 [com.taoensso/timbre "4.7.0"]
                 [robert/hooke "1.3.0"]
                 [reloaded.repl "0.2.2"] ;; FIXME uberjar
                 [org.clojure/tools.namespace "0.3.0-alpha1"] ;; FIXME uberjar
                 [org.clojure/tools.cli "0.3.5"]
                 [me.raynes/fs "1.4.6"]
                 [iota "1.1.3"]
                 [org.clojure/data.csv "0.1.3"]
                 [dk.ative/docjure "1.10.0" :exclusions [commons-codec]]
                 [fast-zip "0.6.1" :exclusions [com.cemerick/austin]]
                 [automat "0.2.0-alpha2"]
                 [org.apache.commons/commons-math3 "3.6.1"]
                 [fipp "0.6.5"]
                 [org.flatland/ordered "1.5.4"] ;; For ordered routes in Swagger
                 [camel-snake-kebab "0.4.0" :exclusions [org.clojure/clojure]]
                 [org.clojure/core.cache "0.6.5"]
                 ;;

                 ;; Stats/models/ML
                 ;; [net.mikera/core.matrix "0.48.0"] ;; >0.44.0 breaks incanter
                 ;; [incanter/incanter-core "1.9.0" :exclusions [net.mikera/core.matrix]]
                 ;; [com.aliasi/lingpipe "4.1.0"] ;; TODO
                 [bigml/sampling "3.0"]
                 [prismatic/plumbing "0.5.3" :exclusions [fs potemkin prismatic/schema]]
                 [lonocloud/synthread "1.4.0" :exclusions [org.clojure/clojurescript]]
                 ;; [prismatic/hiphip "0.1.0"] ;; TODO
                 [cc.qbits/knit "0.3.1"]
                 [org.clojure/core.incubator "0.1.4"]
                 [org.clojure/core.match "0.3.0-alpha5-SNAPSHOT" :exclusions [org.clojure/tools.analyzer.jvm org.clojure/tools.analyzer org.clojure/core.memoize]]
                 ;;;; word2vec/doc2vec TODO:
                 ;;[org.nd4j/nd4j-api "0.0.3.5.5.2" :exclusions [org.slf4j/slf4j-api commons-io]]
                 ;;[org.nd4j/nd4j-jblas "0.0.3.5.5.2"]
                 ;;[org.deeplearning4j/deeplearning4j-core "0.0.3.3.2.alpha1" :exclusions [org.nd4j/nd4j-api commons-io com.fasterxml.jackson.core/jackson-databind]]
                 ;;[org.deeplearning4j/deeplearning4j-scaleout-akka "0.0.3.3.2.alpha1" :exclusions [org.slf4j/slf4j-api commons-io]]
                 ;;[org.deeplearning4j/deeplearning4j-nlp "0.0.3.3.2.alpha1" :exclusions [org.slf4j/slf4j-api commons-io]]
                 ;;

                 ;; Text processing
                 [org.chasen/cabocha "0.69"]
                 [com.ibm.icu/icu4j "57.1"]
                 [d3-compat-tree "0.0.9"]
                 ;;

                 ]
  :plugins [[lein-environ "1.0.3"]]
  :main ^:skip-aot natsume-server.main
  :profiles
  {:dev  [:project/dev  :profiles/dev]
   :test [:project/test :profiles/test]
   :uberjar {:aot :all :resource-paths ["data"]}
   :profiles/dev  {}
   :profiles/test {}
   :project/dev   {:source-paths ["dev"]
                   :repl-options {:init-ns user
                                  :timeout 120000}
                   :dependencies [[reloaded.repl "0.2.2"]
                                  [org.clojure/tools.namespace "0.3.0-alpha1"]]}
   :project/test  {}})
