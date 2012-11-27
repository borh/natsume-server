(defproject natsume-clj "0.3.0-SNAPSHOT"
  :min-lein-version "2.0.0"
  :description "Natsume writing assistance system data processor and server"
  :url "http://hinoki.ryu.titech.ac.jp/natsume/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ["-server" "-XX:+UseConcMarkSweepGC" "-XX:+CMSIncrementalMode" "-XX:+UseCompressedOops" "-XX:+DoEscapeAnalysis" "-XX:+UseBiasedLocking" "-XX:PermSize=64M" "-XX:MaxPermSize=256M" "-Xmx2g"]
  :main natsume-server.core
  :dependencies [[org.clojure/clojure "1.5.0-beta1"]
                 [postgresql "9.1-901.jdbc4"]
                 [org.clojure/java.jdbc "0.2.3"]
                 [korma "0.3.0-beta11"]
                 [com.ibm.icu/icu4j "49.1"]
                 [aleph "0.3.0-SNAPSHOT"]
                 [org.apache.commons/commons-compress "1.4.1"]
                 [com.taoensso/timbre "1.0.0"]
                 [org.clojure/tools.cli "0.2.2"]
                 [fs "1.3.2"]
                 [speclj "2.1.2"]
                 [ring "1.1.6"]
                 [compojure "1.1.3" :exclusions [org.clojure/tools.macro]]
                 [flatland/ring-cors "0.0.7"]
                 [cheshire "5.0.0"]
                 [ring-mock "0.1.3"]]
  :plugins [[speclj "2.3.2"]]
  :test-paths ["spec/"]
  :pedantic :warn)
