(set-env!
 :source-paths #{"src" "dev"}
 :resource-paths #{"data"}
 :dependencies
 '[[org.clojure/clojure "1.9.0"]

   ;;
   [tolitius/boot-check "0.1.6" :scope "test"]
   ;; REPL and config utils
   [adzerk/boot-reload "0.5.2" :scope "test"]
   [org.clojure/tools.nrepl "0.2.13"]
   [mount "0.1.11"]
   [potemkin "0.4.5-alpha1"]
   [aero "1.1.2"]
   [prismatic/schema "1.1.7"]

   ;; Web
   [com.fasterxml.jackson.core/jackson-core "2.9.3"]
   [cheshire "5.8.0"]
   [yada "1.2.10" :exclusions [manifold metosin/ring-swagger]]
   [metosin/ring-swagger "0.25.0"]
   [io.netty/netty-all "4.1.14.Final"]
   [manifold "0.1.7-alpha6"]
   [aleph "0.4.4"]
   [com.taoensso/sente "1.12.0"]
   [com.cognitect/transit-clj "0.8.300"]
   ;; Temporarily until yada-sente integration fixed:
   [ring "1.6.3"]
   [ring/ring-defaults "0.3.1"]
   [ring-cors "0.1.11"]
   [ring/ring-json "0.5.0-beta1"]
   [ring-logger-timbre "0.7.6"]
   [compojure "1.6.0"]
   [buddy/buddy-sign "2.2.0"]
   [buddy/buddy-auth "2.1.0"]
   [restpect "0.2.1" :scope "test"]

   [riddley "0.1.14"]

   [org.clojure/core.async "0.4.474"]

   ;; Database
   [org.postgresql/postgresql "42.1.4"]
   ;; [com.impossibl.pgjdbc-ng/pgjdbc-ng "0.7.1"] ;; TODO
   ;; https://github.com/alaisi/postgres.async another option but would require bigger refactor
   [org.clojure/java.jdbc "0.7.5"]
   [java-jdbc/dsl "0.1.3"]
   [com.alibaba/druid "1.1.6"]
   [honeysql "0.9.1"]
   [com.layerware/hugsql "0.4.8"]

   ;; Utils
   [org.tukaani/xz "1.8"]
   [org.apache.commons/commons-compress "1.15"]
   [org.clojure/tools.reader "1.1.1"]
   [com.taoensso/timbre "4.10.0"]
   [com.fzakaria/slf4j-timbre "0.3.8"]
   [org.slf4j/slf4j-api "1.7.25"]
   [com.taoensso/encore "2.93.0"]
   [robert/hooke "1.3.0"]
   [reloaded.repl "0.2.4" :scope "test"]
   [org.clojure/tools.namespace "0.3.0-alpha4"]
   [org.clojure/tools.cli "0.3.5"]
   [funcool/datoteka "1.0.0"]
   [iota "1.1.3"]
   [org.clojure/data.csv "0.1.4"]
   [dk.ative/docjure "1.12.0" :exclusions [commons-codec]]
   [fast-zip "0.6.1" :exclusions [com.cemerick/austin]]
   [org.apache.commons/commons-math3 "3.6.1"]
   [org.flatland/ordered "1.5.6"] ;; For ordered routes in Swagger
   [camel-snake-kebab "0.4.0" :exclusions [org.clojure/clojure]]
   [org.clojure/core.cache "0.6.5"]
   ;;

   ;; Stats/models/ML
   [bigml/sampling "3.1"]
   [prismatic/plumbing "0.5.5" :exclusions [fs potemkin prismatic/schema]]
   [lonocloud/synthread "1.4.0" :exclusions [org.clojure/clojurescript]]
   [cc.qbits/knit "0.3.1"]
   [org.clojure/core.incubator "0.1.4"]
   [org.clojure/core.match "0.3.0-alpha5" :exclusions [org.clojure/tools.analyzer.jvm org.clojure/tools.analyzer org.clojure/core.memoize]]

   ;; dep overrides
   [instaparse "1.4.8"]
   [joda-time "2.9.9"]
   [com.google.code.findbugs/jsr305 "3.0.2"]
   [commons-io "2.6"]
   [commons-logging "1.2"]
   ;; dl4j
   [com.google.guava/guava "23.6-jre"]
   [org.projectlombok/lombok "1.16.20"]
   [org.nd4j/jackson "0.9.1"]

   [org.nd4j/nd4j-common "0.9.1"]
   [org.nd4j/nd4j-native "0.9.1"]
   [org.nd4j/nd4j-native-platform "0.9.1"]
   ;; [org.nd4j/nd4j-cuda-9.0-platform "0.9.0"]
   [org.deeplearning4j/deeplearning4j-core "0.9.1"
    :exclusions [org.datavec/datavec-data-image]]
   [org.deeplearning4j/deeplearning4j-nlp "0.9.1"]
   ;;
   [de.julielab/jcore-mallet-2.0.9 "2.1.0"]
   [marcliberatore.mallet-lda "0.1.1" :exclusions [cc.mallet/mallet]]
   ;;

   ;; Text processing
   [org.chasen/cabocha "0.69"]
   [com.ibm.icu/icu4j "60.2"]
   [d3-compat-tree "0.0.9"]])

(set-env! :repositories #(conj % ["sonatype-oss-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"]))

(require '[adzerk.boot-reload :refer [reload]]
         'clojure.tools.namespace.repl
         '[tolitius.boot-check :as check]
         ;; '[typedclojure.boot :refer :all]
         '[mount.core :as mount]
         '[aero.core :as aero]
         '[natsume-server.main :as natsume])

(def version "1.0.0-SNAPSHOT")

(load-data-readers!)

(task-options!
 repl {:client true}
 pom {:project 'natsume-server
      :version version
      :description "Natsume writing assistance system data processor and API server"
      :license {"Eclipse Public License" "http://www.eclipse.org/legal/epl-v10.html"}}
 aot {:all true
      #_:namespace #_#{'natsume-server.main}}
 jar {:main 'natsume-server.main
      :file (str "natsume-server-" version "-standalone.jar")}
 sift {:include #{#"natsume-server"}}
 target {:dir #{"static"}})

(deftask check-sources []
  (set-env! :source-paths #{"src"})
  (comp
   (check/with-yagni)
   (check/with-eastwood)
   (check/with-kibit)
   (check/with-bikeshed)))

(comment
  (deftask dev-system
    "Develop the server backend. The system is automatically started in
  the dev profile."
    []
    (require 'reloaded.repl)
    (require 'aero.core)
    (try
      (require 'natsume-server.main)
      (natsume-server.main/run-with-profile :server true)
      (catch Exception e
        (boot.util/fail "Exception while mounting the system\n")
        (boot.util/print-ex e)))
    identity))

(comment
  (deftask dev
    "This is the main development entry point."
    []
    ;; Needed by tools.namespace to know where the source files are
    ;; (apply clojure.tools.namespace.repl/set-refresh-dirs (get-env :directories))
    (comp
     (watch)
     (speak)
     (dev-system)
     (target))))

(defn- run-system [profile dev? extract? extraction-unit extraction-features extraction-file]
  (try
    (require 'natsume-server.main)
    (natsume-server.main/run-with-profile profile dev? extract? extraction-unit extraction-features extraction-file)
    (catch Exception e
      (boot.util/fail "Exception while mounting the system\n")
      (boot.util/print-ex e)))
  identity)

(deftask run [p profile  VAL kw   "Profile"
              d dev          bool "Development"
              e extract      bool "Dataset extraction to local files"
              u unit     VAL kw   "(Extraction only) unit to extract (text|suw|unigrams)"
              f features VAL kw   "(Extraction only) features to extract from tokens (orth|lemma)"
              o out      VAL str  "(Extraction only) output filename (default: corpus-extracted.tsv)"]
  (comp
   (repl :server true
         :init-ns 'natsume-server.main)
   (run-system (or profile :server) (or dev false) (or extract false) (or unit :unigrams) (or features :orth) (or out "corpus-extracted.tsv"))
   (wait)))

(deftask uberjar
  "Build an uberjar"
  []
  (comp
   (aot)
   (pom)
   (uber)
   (jar)
   (sift)
   (target)))
