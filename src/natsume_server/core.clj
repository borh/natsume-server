;; # Commandline interface and logic
(ns natsume-server.core
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [natsume-server.readability :as rd]
            [natsume-server.database :as db]
            [taoensso.timbre :as log]
            [natsume-server.log-config :as lc])
  (:import (java.io.File))
  (:use [clojure.tools.cli :only (cli)])
  (:gen-class))

;; ## Miscellaneous filesystem helper utilities.
(defn normalized-path [path] (.getCanonicalFile path))
(defn base-name [path] (.getName path))
(defn directory? [path] (.isDirectory path))
(defn file? [path] (.isFile path))
(defn extension
  [path]
  (let [base (base-name path)
        i (.lastIndexOf base ".")]
    (when (pos? i)
      (subs base i))))
(defn is-text?
  [path]
  (= (extension path) ".txt"))

(defn get-basename
  [file]
  ((string/split (.getName file) #"\.(?=[^\.]+$)") 0))

#_(defn is-in-database?
    "TODO find a way to log if we discard files here..."
    [f]
    (if (select sources (where {:basename (get-basename f)}))
      true
      false))

;; # Sentence and paragraph splitting
(def delimiter   #"[\.!\?．。！？]")
;; Take care not to use this with JStage data -- temporary hack for BCCWJ
(def delimiter-2 #"[!\?。！？]")
(def closing-quotation #"[\)）」』】］〕〉》\]]") ; TODO
(def opening-quotatoin #"[\(（「『［【〔〈《\[]") ; TODO
(def numbers #"[０-９\d]")
;;(def alphanumerics #"[0-9\uFF10-\uFF19a-zA-Z\uFF41-\uFF5A\uFF21-\uFF3A]")
(def alphanumerics #"[\d０-９a-zA-Zａ-ｚＡ-Ｚ]")

(def sentence-split-re
  (re-pattern
   (format "(?<=%s+)(?!%s+|%s+)"
           delimiter-2
           closing-quotation
           alphanumerics)))

;; This is the first function that deals with the file contents.
;; We need another function that deals with normalization issues using icu4j.
(defn string->paragraphs
  "Splits string into paragraphs.
   Paragraphs are defined as:
   1) one or more non-empty lines delimited by one empty line or BOF/EOF
   2) lines prefixed with fullwidth unicode space '　'"
  [s]
  (string/split s #"([\r\n]{4,}|[\r\n]{2,}　|[\n]{2,})")) ; FIXME

(defn paragraph->sentences-2
  "Splits one pre-formatted paragraph into multiple sentences.
  Pre-formatting means that sentence splitting has already occured."
  [s]
  (remove string/blank? (string/split-lines s)))

(defn paragraph->sentences
  "Splits one paragraph into multiple sentences.

   Since it is hard to use Clojure's regexp functions (string/split)
  because they consume the matched group, we opt to add newlines to
  the end of all delimiters.???"
  [s]
  (vec
   (flatten
    (reduce (fn
              [accum sentence]
              (conj accum
                    (remove string/blank?
                            (flatten (string/split sentence sentence-split-re)))))
            []
            (string/split-lines s)))))

(defn split-on-tab
  [s]
  (string/split s #"\t"))

(defn tsv->vector
  [filename]
  (for
      [line (->> filename slurp string/split-lines (map split-on-tab))]
    line))

(defn string->paragraph->lines
  [s]
  (->> s
       string->paragraphs
       (map paragraph->sentences)))

(defn slurp-with-metadata
  [f]
  (let [basename (get-basename (io/file f))
        source-id (db/basename->source_id basename)
        paragraphs (do (string->paragraph->lines (slurp f)))]
    ;;(log/debug (str "Slurping " f " with source_id " source-id " and # of paragraphs " (count paragraphs)))
    {:source-id source-id
     :paragraphs paragraphs}))

;; # Database and readability connective logic

(defn insert-sentences
  [files-data]
  (let [source-id (:source-id files-data)
        paragraphs (:paragraphs files-data)]
    ;; TODO hook in whole-paragraph readability functions after insertion
    (reduce (fn [paragraph-id ss]
              (doseq [sentence ss]
                (let [sentence-map (merge
                                    (rd/get-sentence-info sentence)
                                    {:text         sentence
                                     :paragraph_id paragraph-id
                                     :sources_id   source-id})]
                  (db/insert-sentence sentence-map rd/average-readability)))
              (do
                (db/insert-paragraph-readability paragraph-id rd/average-readability)
                (inc paragraph-id)))
            (inc (db/last-paragraph-id)) ; previously was just '0'
            paragraphs)
    (db/insert-sources-readability source-id rd/average-readability)))

(defn initialize-corpus
  "Processes given corpus directory."
  [corpus-dir]
  ;; (db/update-genres (tsv->vector (str corpus-dir "/genres.tsv")))
  (let [name (get-basename (io/file corpus-dir)) ;; for debug
        files (->> corpus-dir
                   io/file
                   file-seq
                   (filter is-text?))]
    (log/debug (str "Initializing " name " with files " files))
    (let [basename-set (set (map get-basename files))]
      (db/update-sources (tsv->vector (str corpus-dir "/sources.tsv")) basename-set))
    (do (doseq [sentences (pmap slurp-with-metadata files)] ; compare map and pmap
          ;; (filter db/basename-in-sources?) ;; this is a roundabout
          ;; way of filtering, should be integrated with above db
          ;; update function
          (do (log/debug "Inserting sentences from initialize-corpus")
              (insert-sentences sentences)))
        (db/merge-tokens!) ; TODO fix
        (db/reset-inmemory-tokens!))))

(defn run
  "Initializes database and processes corpus directories from input.
  If no corpus directory is given or the -h flag is present, prints
  out available options and arguments."
  [opts args]
  (log/debug (str "Options:\n" opts "\n"))
  (log/debug (str "Arguments:\n" args "\n"))
  (do
    (db/init-database {:destructive 1})
    (map initialize-corpus (map str args)) ; TODO why map string? fix for multiple dirs
    ;; post-processing hooks go here

    ;; TODO token_freq table --> this should only be done once, after
    ;; that we can load it from the database, so make sure we are not
    ;; doing this every time but as much as possible, save the data
    ;; between runs
    ;; TODO how to do this in the REPL with -main? (time (doall (run [] "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OT")))
    )
  )

(defn process-directories
  "Processes directiors to check if they exist and returns the io/file object with canonical and normalized path.
  Skips non-existing directories."
  [dirs]
  (if-not (empty? dirs)
    (->> dirs
         (map io/file)
         (map normalized-path)
         (filter directory?))))

(defn -main
  "Read files from corpus and do stuff...
   `args` contains corpus directory names and (optional) flags.

  Example usage from REPL: (-main \"/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OT\")"
  [& args]
  (let [[options arguments banner]
        (cli args
             ["-v" "--verbose" "Turn on verbose logging" :default false :flag true]
             ["-l" "--log-directory" "Set logging directory" :default "./log"]
             ["-h" "--help" "Show help" :default false :flag true])]
    (when (:help options)
      (println banner)
      (System/exit 0)) ;; disable when in REPL!
    (if (:verbose options)
      (do
        (println "Turning on verbose logging.")
        (lc/setup-log log/config :debug))
      (lc/setup-log log/config :error))
    (println options)
    (if-let [checked-directories (process-directories arguments)]
      (do
        (println "")
        (run options checked-directories))
      (println banner))))

;; # TODO
;;
;; - refactor so that readability is decoupled from the core of natsume -- managing corpus data and extracting collocations.
