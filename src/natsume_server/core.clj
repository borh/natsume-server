;; # Commandline interface and logic
(ns natsume-server.core
  (:require [clojure.java.io :as io]
            [clojure.core.reducers :as r]

            [clojure.tools.cli :refer [cli]]
            [clojure.data.csv :as csv]
            [me.raynes.fs :as fs]
            [iota :as iota]
            [plumbing.core :refer :all]
            [plumbing.graph :as graph]

            [natsume-server.text :as txt]
            [natsume-server.utils :refer [strict-map-discard]]
            [natsume-server.cabocha-wrapper :as cw]
            [natsume-server.readability :as rd]
            [natsume-server.models.db :as db]
            [natsume-server.models.schema :as schema]

            [taoensso.timbre :as log]
            [natsume-server.log-config :as lc])
  (:import [java.io.File])
  (:gen-class))

;; ## Graph printing helpers
(defn calculate-deps [f]
  (map (fn [parent] [parent (first f)]) (-> f second meta first second first keys)))

(defn print-dot [graph]
  (println (clojure.string/join "\n"
                                (map (fn [r] (str "\""(first r) "\" -> \"" (second r) "\";"))
                                     (mapcat (fn [k] (calculate-deps k)) (graph/->graph graph))))))

;; ## Miscellaneous filesystem helper utilities.
(defn is-text?
  [path]
  (= (fs/extension path) ".txt"))

;; ## Computation graphs
(def corpus-graph
  {:files      (fnk [corpus-dir] (filter is-text? (file-seq corpus-dir)))
   :file-bases (fnk [files] (set (map #(fs/base-name % true) files)))
   :sources    (fnk [corpus-dir] (with-open [sources-reader (io/reader (str corpus-dir "/sources.tsv"))]
                                   (doall (csv/read-csv sources-reader :separator \tab :quote 0))))})

(def file-graph
  {:paragraphs (fnk [filename] (txt/lines->paragraph-sentences (iota/vec (str filename))))
   :sources-id (fnk [filename] (db/basename->source-id (fs/base-name filename true)))})

(def sentence-graph
  {:sentence-features    (fnk [text] (rd/sentence-readability text))
   :sentence-data        (fnk [sentence-features paragraph-id sentence-order-id sources-id]
                              (assoc sentence-features
                                :paragraph-id paragraph-id
                                :sentence-order-id sentence-order-id
                                :sources-id   sources-id))
   ;; The following are side-effecting persistence graphs
   :sentences-id         (fnk [sentence-data] (:id (db/insert-sentence sentence-data)))
   :collocations-id      (fnk [sentence-data sentences-id]
                              (when-let [collocations (seq (:collocations sentence-data))]
                                (:id (db/insert-collocations! collocations sentences-id))))})
;; TODO Take care with side-effecting functions, might not they be optional?

;; ## Graph and database connective logic
(defnk insert-sources! [sources file-bases]
  (db/update-sources! sources file-bases))

(comment "Replaced with functions in sentence-graph above ^^^"
 (defnk insert-collocations! [collocations]
   (when (seq collocations)
     (db/insert-collocations collocations)))

 (defnk insert-sentence! [sentence-data]
   (let [sentences-id (:id (db/insert-sentence sentence-data))]
     (insert-collocations! (merge sentence-data
                                  {:sentences-id sentences-id})))))

(defnk insert-paragraphs! [paragraphs sources-id]
  (loop [paragraphs*       paragraphs
         sentence-start-id 1         ; ids start with 1 (same as SQL pkeys)
         paragraph-id      1]
    (when-let [paragraph (first paragraphs*)]
      (let [sentence-count (count paragraph)
            sentence-end-id (+ sentence-start-id sentence-count)]
        ((comp dorun map) #((graph/eager-compile sentence-graph)
                            {:sources-id        sources-id
                             :sentence-order-id %2
                             :paragraph-id      paragraph-id
                             :text              %1})
         paragraph
         (range sentence-start-id sentence-end-id))
        (recur (next paragraphs*)
               sentence-end-id
               (inc paragraph-id))))))

(defn partition-pmap
  "Like pmap, but runs n futures (default is number of CPUs + 1) partitions of the data."
  ([f coll]
     (partition-pmap (inc (.. Runtime getRuntime availableProcessors)) f coll))
  ([n f coll]
     (let [part-size (/ (count coll) n)]
       (->> coll
            (partition-all part-size)
            ((comp dorun pmap) #(cw/with-new-parser ((comp dorun map) f %)))))))

(defnk insert-sentences! [files]
  (->> ((comp doall map) #((graph/lazy-compile file-graph) {:filename %}) files)
       (partition-pmap insert-paragraphs!)))

(defn process-corpus! [corpus-dir]
  (let [corpus-results ((graph/par-compile corpus-graph) {:corpus-dir corpus-dir})]
    (insert-sources! corpus-results)
    (insert-sentences! corpus-results)))

(def ^:dynamic *write-edn*)

(defn run
  "Initializes database and processes corpus directories from input.
  If no corpus directory is given or the -h flag is present, prints
  out available options and arguments."
  [opts args]
  (log/debug (str "Options:\n" opts "\n"))
  (log/debug (str "Arguments:\n" args "\n"))
  (when (:destructive opts)
    (schema/init-database true))
  (binding [*write-edn* (:cache opts)]
    (strict-map-discard process-corpus! args))
  ;; post-processing hooks go here
  ;; TODO how to do this in the REPL with -main? (time (doall (run [] "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OT")))
  )

(defn process-directories
  "Processes directiors to check if they exist and returns io/file directory objects with canonical and normalized paths.
  Skips non-existing directories and removes duplicates."
  [dirs]
  (if (seq dirs)
    (->> dirs
         (r/map io/file)
         (r/map fs/normalized-path)
         (r/filter fs/directory?)
         (into #{}))))

(defn -main
  "Read files from corpus and do stuff...
   `args` contains corpus directory names and (optional) flags.

  Example usage from REPL: (-main \"/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OT\")"
  [& args]
  (let [[options arguments banner]
        (cli args
             ["-v" "--verbose" "Turn on verbose logging" :default false :flag true]
             ["-l" "--log-directory" "Set logging directory" :default "./log"]
             ["-c" "--cache" "Cache CaboCha processing results" :default false]
             ["-d" "--destructive" "Reset database on run (WARNING: will delete all data)" :default false]
             ["-h" "--help" "Show help" :default false :flag true])]
    (when (:help options)
      (println banner)
      (System/exit 0)) ;; Do not run in REPL!
    (if (:verbose options)
      (do (println "Turning on verbose logging.")
          (lc/setup-log log/config :debug))
      (lc/setup-log log/config :error))
    (if-let [checked-directories (process-directories arguments)]
      (run options checked-directories)
      (println banner))))
