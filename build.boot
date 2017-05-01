(set-env!
 :source-paths #{"src" "dev"}
 :resource-paths #{"data"}
 :dependencies
 '[[org.clojure/clojure "1.9.0-alpha16"]

   ;;
   [tolitius/boot-check "0.1.4" :scope "test"]
   ;; REPL and config utils
   [adzerk/boot-reload "0.5.1" :scope "test"]
   [org.clojure/tools.nrepl "0.2.13"]
   [mount "0.1.11"]
   [potemkin "0.4.3"]
   [aero "1.1.2"]
   [prismatic/schema "1.1.5"]

   ;; Web
   [cheshire "5.7.1"]
   [yada "1.2.2" :exclusions [manifold metosin/ring-swagger]]
   [metosin/ring-swagger "0.23.0"]
   [io.netty/netty-all "4.1.8.Final"]
   [manifold "0.1.6"]
   [aleph "0.4.3"]
   [com.taoensso/sente "1.11.0"]
   [com.cognitect/transit-clj "0.8.300"]
   ;; Temporarily until yada-sente integration fixed:
   [ring "1.6.0-RC3"]
   [ring/ring-defaults "0.3.0-beta3"]
   [ring-cors "0.1.10"]
   [ring-logger-timbre "0.7.5"]
   [compojure "1.6.0-beta3"]
   [buddy/buddy-sign "1.5.0"]
   [buddy/buddy-auth "1.4.1"]

   [org.clojure/core.async "0.3.442"]

   ;; Database
   [org.postgresql/postgresql "9.4.1212"]
   ;; [com.impossibl.pgjdbc-ng/pgjdbc-ng "0.7.1"] ;; TODO
   ;; https://github.com/alaisi/postgres.async another option but would require bigger refactor
   [org.clojure/java.jdbc "0.7.0-alpha3"]
   [java-jdbc/dsl "0.1.3"]
   [com.alibaba/druid "1.0.29"]
   [honeysql "0.8.2"]
   [com.layerware/hugsql "0.4.7"]

   ;; Utils
   [org.tukaani/xz "1.6"]
   [org.apache.commons/commons-compress "1.13"]
   [org.clojure/tools.reader "1.0.0-beta4"]
   [com.taoensso/timbre "4.10.0"]
   [com.taoensso/encore "2.91.0"]
   [robert/hooke "1.3.0"]
   [reloaded.repl "0.2.3"] ;; FIXME uberjar
   [org.clojure/tools.namespace "0.3.0-alpha4"] ;; FIXME uberjar
   [org.clojure/tools.cli "0.3.5"]
   [me.raynes/fs "1.4.6"]
   [iota "1.1.3"]
   [org.clojure/data.csv "0.1.3"]
   [dk.ative/docjure "1.11.0" :exclusions [commons-codec]]
   [fast-zip "0.6.1" :exclusions [com.cemerick/austin]]
   [automat "0.2.0"]
   [org.apache.commons/commons-math3 "3.6.1"]
   [org.flatland/ordered "1.5.4"] ;; For ordered routes in Swagger
   [camel-snake-kebab "0.4.0" :exclusions [org.clojure/clojure]]
   [org.clojure/core.cache "0.6.5"]
   ;;

   ;; Stats/models/ML
   ;; [net.mikera/core.matrix "0.48.0"] ;; >0.44.0 breaks incanter
   ;; [incanter/incanter-core "1.9.0" :exclusions [net.mikera/core.matrix]]
   ;; [com.aliasi/lingpipe "4.1.0"] ;; TODO
   [bigml/sampling "3.1"]
   [prismatic/plumbing "0.5.4" :exclusions [fs potemkin prismatic/schema]]
   [lonocloud/synthread "1.4.0" :exclusions [org.clojure/clojurescript]]
   ;; [prismatic/hiphip "0.1.0"] ;; TODO
   [cc.qbits/knit "0.3.1"]
   [org.clojure/core.incubator "0.1.4"]
   [org.clojure/core.match "0.3.0-alpha4" :exclusions [org.clojure/tools.analyzer.jvm org.clojure/tools.analyzer org.clojure/core.memoize]]
                 ;;;; word2vec/doc2vec TODO:
   ;;[org.nd4j/nd4j-api "0.0.3.5.5.2" :exclusions [org.slf4j/slf4j-api commons-io]]
   ;;[org.nd4j/nd4j-jblas "0.0.3.5.5.2"]
   [byte-streams "0.2.2"]
   [org.nd4j/nd4j-native "0.8.0" :exclusions [org.javassist/javassist]]
   [org.nd4j/nd4j-native-platform "0.8.0" :exclusions [org.javassist/javassist]]
   [org.deeplearning4j/deeplearning4j-core "0.8.0" :exclusions [org.nd4j/nd4j-api commons-io com.fasterxml.jackson.core/jackson-databind com.fasterxml.jackson.datatype/jackson-datatype-joda com.google.guava/guava org.apache.commons/commons-lang3]]
   ;;[org.deeplearning4j/deeplearning4j-scaleout-akka "0.4-rc3.9" :exclusions [org.slf4j/slf4j-api commons-io]]
   [org.deeplearning4j/deeplearning4j-nlp "0.8.0" :exclusions [org.slf4j/slf4j-api commons-io commons-codec]]
   [cc.mallet/mallet "2.0.8"]
   [marcliberatore.mallet-lda "0.1.1" :exclusions [cc.mallet/mallet]]
   ;;[net.sf.trove4j/trove4j "2.0.2"] ;; needed by mallet master
   [org.apache.lucene/lucene-core "6.5.1"]
   ;;

   ;; Text processing
   [org.chasen/cabocha "0.69"]
   [com.ibm.icu/icu4j "58.2"]
   [d3-compat-tree "0.0.9"]])

(set-env! :repositories #(conj % ["sonatype-oss-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"]))

(require '[adzerk.boot-reload :refer [reload]]
         'clojure.tools.namespace.repl
         '[tolitius.boot-check :as check]
         '[mount.core :as mount]
         '[aero.core :as aero]
         '[natsume-server.main :as natsume])

(def repl-port 5600)
(def version "1.0.0-SNAPSHOT")

(load-data-readers!)

(task-options!
 repl {:client true
       :port repl-port}
 pom {:project 'natsume-server
      :version version
      :description "Natsume writing assistance system data processor and API server"
      :license {"Eclipse Public License" "http://www.eclipse.org/legal/epl-v10.html"}}
 aot {:namespace #{'natsume-server.main}}
 jar {:main 'natsume-server.main
      :file (str "natsume-server-" version "-standalone.jar")})

(deftask check-sources []
  (set-env! :source-paths #{"src"})
  (comp
   (check/with-yagni)
   (check/with-eastwood)
   (check/with-kibit)
   (check/with-bikeshed)))

(deftask dev-system
  "Develop the server backend. The system is automatically started in
  the dev profile."
  []
  (require 'reloaded.repl)
  (require 'aero.core)
  (try
    #_(require 'user)
    #_(user/go)
    (require 'natsume-server.main)
    (natsume-server.main/run-with-profile :server true)
    (catch Exception e
      (boot.util/fail "Exception while mounting the system\n")
      (boot.util/print-ex e)))
  identity)

(deftask dev
  "This is the main development entry point."
  []
  (set-env! :dependencies #(vec (concat % '[[reloaded.repl "0.2.3"]])))

  ;; Needed by tools.namespace to know where the source files are
  (apply clojure.tools.namespace.repl/set-refresh-dirs (get-env :directories))

  (comp
   (watch)
   (speak)
   (dev-system)
   (target)))

(deftask build
  []
  (target :dir #{"static"}))

(defn- run-system [profile dev?]
  (println (format "Running system with profile %s in %s mode" profile (if dev? "dev" "prod")))
  (try
    (require 'natsume-server.main)
    (natsume-server.main/run-with-profile profile dev?)
    (catch Exception e
      (boot.util/fail "Exception while mounting the system\n")
      (boot.util/print-ex e)))
  identity)

(deftask run [p profile VAL kw   "Profile"
              d dev         bool "Development"]
  (comp
   (repl :server true
         :port repl-port
         :init-ns 'natsume-server.main)
   (run-system (or profile :prod-server) (or dev false))
   (wait)))

(deftask uberjar
  "Build an uberjar"
  []
  (comp
   (aot)
   (pom)
   (uber)
   (jar)
   (target)))
