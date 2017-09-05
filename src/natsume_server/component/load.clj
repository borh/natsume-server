(ns natsume-server.component.load
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.core.reducers :as r]
            [clojure.string :as string]

            [natsume-server.nlp.text :as text]
            [natsume-server.nlp.importers.bccwj :as bccwj]
            [natsume-server.nlp.importers.wikipedia :as wikipedia]
            [natsume-server.nlp.annotation-middleware :as am]
            [natsume-server.nlp.readability :as rd]
            [natsume-server.component.database :as db :refer [connection database-init]]
            [natsume-server.component.query :as q]
            [natsume-server.config :refer [config]]

            [bigml.sampling.simple :as sampling]
            [plumbing.core :refer [map-keys fnk defnk ?>>]]
            [plumbing.graph :as graph]
            [clojure.data.csv :as csv]
            [datoteka.core :as fs]
            [iota :as iota]
            [schema.core :as s]

            [taoensso.timbre :as timbre]
            [mount.core :refer [defstate]])
  (:import [natsume_server.nlp.cabocha_wrapper Chunk]
           [java.io File]))

(defn base-name [s]
  (-> s
      (fs/path)
      (fs/name)
      (fs/split-ext)
      (first)))

(defn walk-path
  ([path]
   (walk-path path nil))
  ([path filter-ext]
   (let [files (tree-seq
                (fn [p] (and (class p) (fs/directory? p)))
                fs/list-dir
                (fs/path path))
         filter-fn (if filter-ext
                     #(and (class %) (not (fs/directory? %)) (= filter-ext (fs/ext %)))
                     #(and (class %) (not (fs/directory? %))))]
     (filter filter-fn files))))

(def sentence-graph
  {:tree            (fnk get-tree :- [Chunk] [text :- s/Str] (am/sentence->tree text))
   :features        rd/sentence-readability
   ;; The following are side-effecting persistence graphs:
   :sentences-id    (fnk get-sentences-id :- s/Num
                      [conn features tags paragraph-order-id sentence-order-id sources-id]
                      (-> (q/insert-sentence conn
                                             (assoc features
                                                    :tags (into #{} (map name tags))
                                                    :paragraph-order-id paragraph-order-id
                                                    :sentence-order-id sentence-order-id
                                                    :sources-id sources-id))
                          first
                          :id))
   :collocations-id (fnk get-collocations-id :- (s/maybe [s/Num]) [conn features sentences-id]
                      (when-let [collocations (seq (:collocations features))]
                        (map :id (q/insert-collocations! conn collocations sentences-id))))
   :commit-tokens   (fnk commit-tokens :- nil [conn tree sentences-id]
                      (q/insert-tokens! conn (flatten (map :tokens tree)) sentences-id))
   :commit-unigrams (fnk commit-unigrams :- nil [conn features sentences-id]
                      (q/insert-unigrams! conn (:unigrams features) sentences-id))})
(def sentence-graph-fn (graph/eager-compile sentence-graph))

(defnk insert-paragraphs! [conn paragraphs sources-id]
  (loop [paragraphs*       paragraphs
         sentence-start-id 1         ; ids start with 1 (same as SQL pkeys)
         paragraph-id      1]
    (when-let [paragraph (first paragraphs*)]
      (let [{:keys [sentences tags]} paragraph
            sentence-count (count sentences)
            sentence-end-id (+ sentence-start-id sentence-count)]
        ((comp dorun map)
         (s/fn [text :- s/Str sentence-order-id :- s/Num]
           (sentence-graph-fn
            {:conn               conn
             :tags               tags
             :sources-id         sources-id
             :sentence-order-id  sentence-order-id
             :paragraph-order-id paragraph-id
             :text               text}))
          sentences
          (range sentence-start-id sentence-end-id))
        (recur (next paragraphs*)
               sentence-end-id
               (inc paragraph-id))))))

(def file-graph
  {:paragraphs (fnk [filename] (-> filename str #_iota/vec iota/seq text/lines->paragraph-sentences text/add-tags))
   :sources-id (fnk [conn filename] (q/basename->source-id conn (base-name filename)))
   :persist    insert-paragraphs!})
(def file-graph-fn (graph/eager-compile file-graph))

(def bccwj-file-graph
  (assoc file-graph
         :corpus (fnk [filename]
                   (subs (base-name filename) 0 2))
         :paragraphs (fnk [filename corpus]
                       (case (fs/ext filename)
                         "txt" (-> filename str iota/vec text/lines->paragraph-sentences text/add-tags)
                         "xml" (bccwj/xml->paragraph-sentences filename corpus)))))
(def bccwj-file-graph-fn (graph/eager-compile bccwj-file-graph))

(def wikipedia-file-graph-fn (graph/eager-compile (dissoc file-graph :paragraphs)))

;; ## Graph and database connective logic
(defn sample [{:keys [ratio seed replace]} data]
  (let [total (count data)]
    (take (inc (int (* ratio total))) (sampling/sample data :seed seed :replace replace))))

(defn dorunconc
  [f coll]
  ((comp dorun (partial pmap f)) coll))

(def corpus-graph
  ;; :files and :persist should be overridden for Wikipedia and BCCWJ.
  {:files      (fnk [corpus-dir sampling-options]
                 (let [all-files (->> (walk-path corpus-dir "txt")
                                      (into #{}))
                       sampled-files (if (= (:ratio sampling-options) 0.0)
                                       all-files
                                       (sample sampling-options all-files))]
                   (timbre/info "Processing generic corpus" corpus-dir "using" (count sampled-files) "out of" (count all-files) "files.")
                   sampled-files))
   :file-bases (fnk [files] (set (map base-name files)))
   :sources    (fnk [corpus-dir]
                 (map
                  (fn [[title author year basename genres-name subgenres-name
                        subsubgenres-name subsubsubgenres-name permission]]
                    {:title      title
                     :author     author
                     :year       (Integer/parseInt year)
                     :basename   basename
                     :genre      [genres-name subgenres-name subsubgenres-name subsubsubgenres-name]
                     :permission (Boolean/valueOf permission)})
                  (with-open [sources-reader (io/reader (str corpus-dir "/sources.tsv"))]
                    (doall (csv/read-csv sources-reader :separator \tab :quote 0)))))
   :persist    (fnk [conn sources files file-bases]
                 ;; For non-BCCWJ and Wikipedia sources, we might want to run some sanity checks first.
                 (let [sources-basenames (set (map :basename sources))
                       basenames-missing-source (set/difference file-bases sources-basenames)
                       basenames-missing-from-fs (set/difference sources-basenames file-bases)]
                   (binding [*print-length* 10]
                     (when (seq basenames-missing-source)
                       (timbre/debugf "%d basenames missing from sources.tsv: (Warning: will be skipped!) %s"
                                      (count basenames-missing-source)
                                      basenames-missing-source))
                     (when (seq basenames-missing-from-fs)
                       (timbre/debugf "%d basenames in sources.tsv missing on filesystem: %s"
                                      (count basenames-missing-from-fs)
                                      basenames-missing-from-fs)))
                   (q/insert-sources! conn sources (set/difference file-bases basenames-missing-source))
                   (->> files
                        (remove (fn [f] (contains? basenames-missing-source (base-name f))))
                        (dorunconc #(file-graph-fn {:conn conn :filename %})))))})

(def wikipedia-graph
  (merge (dissoc corpus-graph :file-bases :sources)
         {:files   (fnk [corpus-dir sampling-options]
                     (->> (walk-path corpus-dir "xml")
                          (mapcat wikipedia/doc-seq) ; Should work for split and unsplit Wikipedia dumps.
                          (?>> (not= (:ratio sampling-options) 0.0) (take (int (* (:ratio sampling-options) 1070383)))))) ; number is for Wikipedia as of 2017/08/01.
          :persist (fnk [conn files]
                     (->> files
                          (dorunconc (fn [file]
                                       (let [{:keys [sources paragraphs]} file]
                                         (q/insert-source! conn sources)
                                         (wikipedia-file-graph-fn {:conn conn
                                                                   :filename (:basename sources)
                                                                   :paragraphs paragraphs}))))))}))

(def bccwj-graph
  (merge corpus-graph
         {:files   (fnk [corpus-dir sampling-options]
                     (let [all-files (->> (walk-path corpus-dir "xml")
                                          (into #{}))
                           sampled-files (if (= (:ratio sampling-options) 0.0)
                                           all-files
                                           (sample sampling-options all-files))]
                       (timbre/info "Processing BCCWJ corpus" corpus-dir "using" (count sampled-files) "out of" (count all-files) "files.")
                       sampled-files))
          :persist (fnk [conn sources files file-bases]
                     (q/insert-sources! conn sources file-bases)
                     (->> files
                          (dorunconc #(bccwj-file-graph-fn {:conn conn :filename %}))))}))

(s/defn process-corpus! :- nil
  [conn :- s/Any
   sampling :- {:ratio s/Num :seed s/Num :hold-out s/Bool :replace s/Bool}
   corpus-dir :- File]
  (let [corpus-computation (graph/eager-compile
                            (condp re-seq (.toString (fs/name corpus-dir))
                              #"(?i)wiki" wikipedia-graph
                              #"(?i)(LB|OB|OC|OL|OM|OP|OT|OV|OW|OY|PB|PM|PN)" bccwj-graph
                              corpus-graph))]
    (corpus-computation {:conn conn
                         :corpus-dir corpus-dir
                         :sampling-options sampling #_(env :sampling)})))

(s/defn process-directories :- #{File}
  "Processes directories to check if they exist and returns a set of io/file directory objects with canonical and normalized paths."
  [dirs :- [s/Str]]
  (if (seq dirs)
    (into #{}
          (comp (map fs/path)
                (map fs/normalize)
                (filter fs/directory?))
          dirs)))

(s/defn process :- nil
  "Initializes database and processes corpus directories from input.
  If no corpus directory is given or the -h flag is present, prints
  out available options and arguments."
  [conn :- s/Any
   dirs :- [s/Str]
   sampling :- {:ratio s/Num :seed s/Num :hold-out s/Bool :replace s/Bool}]
  ((comp dorun map) (partial process-corpus! conn sampling) (process-directories dirs)))

(defstate data
  :start (let [{:keys [dirs sampling search]} config]
           (when (:process config)
             (process db/connection dirs sampling))
           (when search
             (db/create-search-tables! db/connection))))
