(defproject natsume-clj "0.3.0-SNAPSHOT"
  :min-lein-version "2.0.0"
  :description "Natsume writing assistance system data processor and server"
  :url "http://hinoki.ryu.titech.ac.jp/natsume/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ["-server" "-XX:+UseConcMarkSweepGC" "-XX:+CMSIncrementalMode" "-XX:+UseCompressedOops" "-XX:+DoEscapeAnalysis" "-XX:+UseBiasedLocking" "-XX:PermSize=64M" "-XX:MaxPermSize=256M" "-Xmx2g"]
  :main natsume-server.core
  :dependencies [[org.clojure/clojure "1.5.0-beta1"]
                 [org.clojure/java.jdbc "0.2.3"]
                 [postgresql "9.1-901.jdbc4"]
                 [korma "0.3.0-beta11"]
                 [com.ibm.icu/icu4j "49.1"]
                 [aleph "0.3.0-beta5"]
                 [org.apache.commons/commons-compress "1.4.1"]
                 [com.taoensso/timbre "1.0.0"]
                 [org.clojure/tools.cli "0.2.2"]
                 [midje "1.5-alpha2"]
                 [ring "1.1.6"]
                 [compojure "1.1.3"]
                 [ring-middleware-format "0.2.2"]]
  :dev-dependencies [[com.stuartsierra/lazytest "1.2.3"]])
