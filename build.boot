(require '[clojure.java.shell :as sh])

(defn next-version [version]
  (when version
    (let [[a b] (next (re-matches #"(.*?)([\d]+)" version))]
      (when (and a b)
        (str a (inc (Long/parseLong b)))))))

(defn deduce-version-from-git
  "Avoid another decade of pointless, unnecessary and error-prone
  fiddling with version labels in source code."
  []
  (let [[version commits hash dirty?]
        (next (re-matches #"(.*?)-(.*?)-(.*?)(-dirty)?\n"
                          (:out (sh/sh "git" "describe" "--dirty" "--long" "--tags" #_"--match" #_"v?[0-9].*"))))]
    (try
      (cond
        dirty? (str (next-version version) "-" hash "-dirty")
        (pos? (Long/parseLong commits)) (str (next-version version) "-" hash)
        :otherwise version)
      (catch Exception e (println "Not a git repository or empty repository (did you forget to tag?). Please git init in this directory/make a commit and tag a version.")))))

(def project "natsume-server")
(def version (deduce-version-from-git))

(set-env!
 :source-paths #{"test"}
 :resource-paths #{"src" "data"}
 :dependencies
 '[[seancorfield/boot-tools-deps "0.4.5" :scope "test"]
   [tolitius/boot-check "0.1.9" :scope "test"]
   [adzerk/bootlaces "0.1.13" :scope "test"]
   [adzerk/boot-test "1.2.0" :scope "test"]
   ;; REPL and config utils
   [adzerk/boot-reload "0.5.2" :scope "test"]]
 :repositories #(conj % ["sonatype-oss-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"]))

(task-options!
 repl {:client true}
 pom {:project     (symbol project)
      :version     version
      :description "Natsume writing assistance system data processor and API server"
      :license     {"Eclipse Public License" "http://www.eclipse.org/legal/epl-v10.html"}
      :url         "https://hinoki-project.org/natsume"
      :scm         {:url "https://github.com/borh/natsume-server"}}
 aot {:all true}
 jar {:main 'natsume-server.main
      :file (str project "-" version ".jar")})

(require '[adzerk.boot-reload :refer [reload]])
(require '[tolitius.boot-check :as check])
(require '[boot-tools-deps.core :refer [deps]])
(require '[adzerk.bootlaces :refer :all])

(bootlaces! version)

(load-data-readers!)

(deftask check-sources []
  (set-env! :source-paths #{"src"})
  (comp
   (check/with-yagni)
   (check/with-eastwood)
   (check/with-kibit)
   (check/with-bikeshed)))

(deftask build []
  (comp (deps :quick-merge true) (pom) (jar) (target) (install)))

(deftask dev []
  (comp (watch) (deps :aliases [:test]) (repl :init-ns 'natsume-server.main :server true)))

(require '[adzerk.boot-test :as boot-test])
(deftask test []
  (comp (deps :aliases [:test] :quick-merge true)
        (boot-test/test)))

(deftask uberjar
  "Build an uberjar"
  []
  (comp
   (deps :quick-merge true)
   (aot)
   (pom)
   (uber)
   (jar)
   (sift)
   (target)))
