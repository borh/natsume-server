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
  :dependencies [[org.clojure/clojure "1.7.0-alpha4"]

                 [compojure "1.3.1" :exclusions [clj-time commons-codec]]
                 [metosin/compojure-api "0.17.0"]
                 [metosin/ring-http-response "0.5.2"]
                 [metosin/ring-swagger-ui "2.0.24"]
                 [prone "0.8.0"]
                 [prismatic/schema "0.3.7"]
                 [ring "1.3.2"]
                 [ring/ring-defaults "0.1.3"]
                 [ring-webjars "0.1.0"]
                 [http-kit "2.1.19"]

                 ;; Database
                 [org.postgresql/postgresql "9.4-1200-jdbc41-SNAPSHOT"] ; https://github.com/kdubb/pgjdbc-ng
                 [org.clojure/java.jdbc "0.3.6"]
                 [java-jdbc/dsl "0.1.1"]
                 [com.alibaba/druid "1.0.13"]
                 [honeysql "0.4.3"]
                 [yesql "0.4.0" :exclusions [instaparse]]
                 ;;

                 ;; Utils
                 [org.tukaani/xz "1.5"]
                 [org.apache.commons/commons-compress "1.9"]
                 [org.clojure/tools.reader "0.8.13"]
                 [com.taoensso/timbre "3.3.1"]
                 [clj-configurator "0.1.5"]
                 [org.dave/cfg "1.0.0"]
                 [org.clojure/tools.cli "0.3.1"]
                 [me.raynes/fs "1.4.6"]
                 [iota "1.1.2"]
                 [org.clojure/data.csv "0.1.2"]
                 [dk.ative/docjure "1.8.0"]
                 [org.flatland/useful "0.11.3" :exclusions [org.clojure/tools.macro]]
                 [fast-zip "0.5.2"]
                 [org.apache.commons/commons-math3 "3.4.1"]
                 [fipp "0.5.2"]
                 [camel-snake-kebab "0.3.0" :exclusions [org.clojure/clojure]]
                 ;;

                 ;; Stats/models/ML
                 [incanter "1.9.0"]
                 ;;[org.clojure/math.numeric-tower "0.0.2"]
                 [com.aliasi/lingpipe "4.1.0"]
                 ;; [clj-liblinear "0.0.1-SNAPSHOT"] ; TODO https://github.com/lynaghk/clj-liblinear
                 [bigml/sampling "3.0"]
                 [prismatic/plumbing "0.3.7" :exclusions [fs potemkin prismatic/schema]]
                 ;; [prismatic/hiphip "0.1.0"] ;; TODO
                 [cc.qbits/knit "0.2.1"]
                 [org.clojure/core.incubator "0.1.3"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 ;;[org.clojure/core.logic "0.8.3"] ; TODO
                 ;;[pldb "0.1.1"]
                 ;;[readyforzero/babbage "1.0.2"] ; TODO
                 ;; word2vec/doc2vec TODO:
                 [org.nd4j/nd4j-api "0.0.3.5.5.2" :exclusions [org.slf4j/slf4j-api commons-io]]
                 [org.nd4j/nd4j-jblas "0.0.3.5.5.2"]
                 [org.deeplearning4j/deeplearning4j-core "0.0.3.3.2.alpha1" :exclusions [org.nd4j/nd4j-api commons-io]]
                 [org.deeplearning4j/deeplearning4j-scaleout-akka "0.0.3.3.2.alpha1" :exclusions [org.slf4j/slf4j-api commons-io]]
                 [org.deeplearning4j/deeplearning4j-nlp "0.0.3.3.2.alpha1" :exclusions [org.slf4j/slf4j-api commons-io]]
                 ;;

                 ;; Text processing
                 [org.chasen/cabocha "0.68"]
                 [com.ibm.icu/icu4j "54.1.1"]
                 [d3-compat-tree "0.0.3.1"]
                 ;;

                 [com.stuartsierra/component "0.2.2"]

                 [potemkin "0.3.12-SNAPSHOT"]
                 [duct "0.1.0"]
                 [environ "1.0.0"]
                 [meta-merge "0.1.1"]]
  :plugins [[lein-environ "1.0.0"]
            [lein-gen "0.2.2"]]
  :generators [[duct/generators "0.0.3"]]
  :duct {:ns-prefix natsume-server}
  :main ^:skip-aot natsume-server.main
  :aliases {"gen" ["generate"]}
  :profiles
  {;;:defaults [:base :system :user :provided :dev :profiles/dev]
   :dev  [:project/dev  :profiles/dev]
   :test [:project/test :profiles/test]
   :uberjar {:aot :all :resource-paths ["swagger-ui"]}
   :profiles/dev  {}
   :profiles/test {}
   :project/dev   {:source-paths ["dev"]
                   :repl-options {:init-ns user}
                   :dependencies [[reloaded.repl "0.1.0"]
                                  [org.clojure/tools.namespace "0.2.9"]
                                  [kerodon "0.5.0"]]
                   ;;:env {:port 3000}
                   }
   :project/test  {}})
