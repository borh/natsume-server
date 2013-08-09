;; # Commandline interface and logic
(ns natsume-server.core
  (:require [clojure.java.io :as io]
            [clojure.core.reducers :as r]
            [clojure.set :as set]
            [natsume-server.config :as cfg]
            [clojure.tools.cli :refer [cli]]
            [clojure.data.csv :as csv]
            [me.raynes.fs :as fs]
            [iota :as iota]
            [bigml.sampling.simple :as sampling]
            [plumbing.core :refer :all]
            [plumbing.graph :as graph]
            [qbits.knit :as knit]

            [natsume-server.text :as text]
            [natsume-server.importers.bccwj :as bccwj]
            [natsume-server.importers.wikipedia :as wikipedia]
            [natsume-server.annotation-middleware :as am]
            [natsume-server.readability :as rd]
            [natsume-server.models.db :as db]
            [natsume-server.models.schema :as schema]
            [natsume-server.lm :as lm]
            [natsume-server.api.main :as api]

            [taoensso.timbre :as log]
            [natsume-server.log-config :as lc]))

;; ## Computation graphs / pipeline pattern
(def sentence-graph
  {:tree     (fnk [text] (am/sentence->tree text))
   :features (fnk [tree text] (rd/sentence-readability tree text))
   ;; The following are side-effecting persistence graphs:
   :sentences-id    (fnk [features tags paragraph-order-id sentence-order-id sources-id]
                         (-> (db/insert-sentence (assoc features
                                                   :tags tags
                                                   :paragraph-order-id paragraph-order-id
                                                   :sentence-order-id sentence-order-id
                                                   :sources-id sources-id))
                             first
                             :id))
   :collocations-id (fnk [features sentences-id]
                         (when-let [collocations (seq (:collocations features))]
                           (map :id (db/insert-collocations! collocations sentences-id))))
   :tokens          (fnk [tree sentences-id]
                         (db/insert-tokens! (flatten (map :tokens tree)) sentences-id))})
(def sentence-graph-fn (graph/eager-compile sentence-graph))

(defnk insert-paragraphs! [paragraphs sources-id]
  (loop [paragraphs*       paragraphs
         sentence-start-id 1         ; ids start with 1 (same as SQL pkeys)
         paragraph-id      1]
    (when-let [paragraph (first paragraphs*)]
      (let [{:keys [sentences tags]} paragraph
            sentence-count (count sentences)
            sentence-end-id (+ sentence-start-id sentence-count)]
        ((comp dorun map) #(sentence-graph-fn
                         {:tags               tags
                          :sources-id         sources-id
                          :sentence-order-id  %2
                          :paragraph-order-id paragraph-id
                          :text               %1})
         sentences
         (range sentence-start-id sentence-end-id))
        (recur (next paragraphs*)
               sentence-end-id
               (inc paragraph-id))))))

(def file-graph
  {:paragraphs (fnk [filename] (-> filename str #_iota/vec iota/seq text/lines->paragraph-sentences text/add-tags))
   :sources-id (fnk [filename] (db/basename->source-id (fs/base-name filename true)))
   :persist    insert-paragraphs!})
(def file-graph-fn (graph/eager-compile file-graph))

(def bccwj-file-graph
  (assoc file-graph
    :paragraphs (fnk [filename]
                     (case (fs/extension filename)
                       ".txt" (-> filename str iota/vec text/lines->paragraph-sentences text/add-tags)
                       ".xml" (bccwj/xml->paragraph-sentences filename)))))
(def bccwj-file-graph-fn (graph/eager-compile bccwj-file-graph))

(def wikipedia-file-graph-fn (graph/eager-compile (dissoc file-graph :paragraphs)))

;; ## Graph and database connective logic
(defn sample [{:keys [ratio seed replace]} data]
  (let [total (count data)]
    (take (int (* ratio total)) (sampling/sample data :seed seed :replace replace))))

(def corpus-graph
  ;; :files and :persist should be overridden for Wikipedia and BCCWJ.
  {:files      (fnk [corpus-dir sampling-options]
                    (->> corpus-dir
                         file-seq
                         (filter #(= ".txt" (fs/extension %)))
                         (?>> (not= (:ratio sampling-options) 0.0) (partial sample sampling-options))))
   :file-bases (fnk [files] (set (map #(fs/base-name % true) files)))
   :sources    (fnk [corpus-dir]
                    (with-open [sources-reader (io/reader (str corpus-dir "/sources.tsv"))]
                      (doall (csv/read-csv sources-reader :separator \tab :quote 0))))
   :persist    (fnk [sources files file-bases]
                    (db/update-sources! sources file-bases)
                    (->> ((comp doall map) #(file-graph-fn {:filename %}) files)
                         ((comp dorun pmap) insert-paragraphs!)))})

(def wikipedia-graph
  (merge (dissoc corpus-graph :file-bases :sources)
         {:files   (fnk [corpus-dir sampling-options]
                        (->> corpus-dir
                             file-seq
                             (filter #(= ".xml" (fs/extension %)))
                             (mapcat wikipedia/doc-seq) ; Should work for split and unsplit Wikipedia dumps.
                             (take (int (* (:ratio sampling-options) 853975))))) ; 853975 is for Wikipedia as of 2013/03/07.
          :persist (fnk [files]
                        (->> files
                             (partition-all (inc (.. Runtime getRuntime availableProcessors)))
                             ((comp dorun pmap) #((comp dorun pmap)
                                                  (fn [file]
                                                    (let [{:keys [sources paragraphs]} file]
                                                      (db/update-source! sources)
                                                      (wikipedia-file-graph-fn {:filename (:basename sources)
                                                                                :paragraphs paragraphs})))
                                                  %))))}))

(def bccwj-graph
  (merge corpus-graph
         {:files   (fnk [corpus-dir sampling-options]
                        (->> corpus-dir
                             file-seq
                             (filter #(= ".xml" (fs/extension %)))
                             (?>> (not= (:ratio sampling-options) 0.0) (partial sample sampling-options))))
          ;; TODO override :sources to add tags as separate genres????? -> this would mean doing genre at the paragraph level!?
          :persist (fnk [sources files file-bases]
                        (db/update-sources! sources file-bases)
                        (->> files
                             ((comp doall pmap) #(bccwj-file-graph-fn {:filename %}))
                             ((comp dorun pmap) insert-paragraphs!)))}))

(defn process-corpus!
  [corpus-dir]
  (let [corpus-computation (graph/eager-compile
                            (condp #(re-seq %1 %2) (.getPath corpus-dir)
                              #"(?i)wiki" wikipedia-graph
                              #"(?i)(LB|OB|OC|OL|OM|OP|OT|OV|OW|OY|PB|PM|PN)" bccwj-graph
                              corpus-graph))]
    (corpus-computation {:corpus-dir corpus-dir
                         :sampling-options (cfg/opt :sampling)})))

(defn run
  "Initializes database and processes corpus directories from input.
  If no corpus directory is given or the -h flag is present, prints
  out available options and arguments."
  [args]
  ((comp dorun map) process-corpus! args))

(defn process-directories
  "Processes directories to check if they exist and returns io/file directory objects with canonical and normalized paths.
  Skips non-existing directories and removes duplicates."
  [dirs]
  (if (seq dirs)
    (->> dirs
         (r/map io/file)
         (r/map fs/normalized-path)
         (r/filter fs/directory?)
         (into #{}))))

(defn- usage []
  (println "natsume-server version" (System/getProperty "natsume-server.version"))
  (println "USAGE: ")
  (println "    lein run </path/to/corpora/> [options]\n")
  (cfg/print-help)
  (System/exit 0))

(defn -main
  "Read files from corpus and do stuff...
   `args` contains corpus directory names and (optional) flags.

  Example usage from REPL: (-main \"/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OT\")"
  [& args]
  (let [target-dirs (try (cfg/parse-cli-args! args)
                         (catch IllegalArgumentException e (do (println e) (usage))))]

    (if (cfg/opt :verbose)
      (do (println (cfg/opt))
          (println "Turning on verbose logging.")
          (lc/setup-log log/config :debug))
      (lc/setup-log log/config :error))

    (when (and (empty? target-dirs) (not (or (cfg/opt :server :run)
                                             (cfg/opt :search)
                                             (cfg/opt :clean))))
      (usage))

    (when (cfg/opt :server :run)
      (start-server!))

    (when (cfg/opt :clean)
      (schema/drop-all-cascade!)
      (schema/init-database!))

    (when-not (cfg/opt :no-process)
      (if-let [checked-directories (process-directories target-dirs)]
        (run checked-directories)
        (usage)))

    (when (cfg/opt :search)
      (schema/create-search-tables!))

    (when (cfg/opt :models :n-gram)
      (lm/compile-all-models!))))
