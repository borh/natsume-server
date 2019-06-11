(ns natsume-server.nlp.importers.wikipedia
  (:require [clojure.java.io :as io]
            [natsume-server.nlp.text :as text]
            [natsume-server.models.corpus :as corpus]
            [natsume-server.utils.fs :as fs]))

;; FIXME this is an import, needs fixing:
;;       - better names
;;       - stream documents, stream metadata (sources) --> lazy seq of documents-sentences
;;       - need to refactor the file reading part of core.clj/text.clj into simple-text-importer (specific part) + general part (used by all importers)
;;       - Ideally, we should be using one of the Java wikimedia dump libraries (that might also let us tag sentences (:title, :quotation, ...))
;;           - http://code.google.com/p/wikixmlj/

;; [{:tags #{:some-tag, :another-tag},
;;   :sentences ["First.", "Second sentence."]},
;;  {:tags #{ ... },
;;   :sentences [ ... ]}]

(defn is-open? [s]
  (re-seq #"^<doc id" s))

(defn is-close? [s]
  (re-seq #"^</doc>$" s))

(defn extract-header [s]
  (->> s
       (re-seq #"^<doc id=\"(\d+)\" url=\".+\" title=\"(.+)\">$")
       first
       rest
       vec))

(defn make-sources-record [title year id]
  #:metadata{:title      title
             :author     ""
             :year       year
             :basename   id
             :genre      ["Wikipedia"]
             :permission true})

(defn process-doc [[[header] lines]]
  #:document{:metadata   (let [[id title] (extract-header header)]
                           (make-sources-record title 2019 id)) ; Year is not specified in file -> last modified date of article?
             :paragraphs (->> lines
                              drop-last                     ; Drop closing </doc>, split and add (dummy) tags.
                              ;; Newer versions of WikiExtractor add bullet point markup. Remove it:
                              #_(map #(string/replace % "BULLET::::" ""))
                              text/lines->paragraph-sentences
                              text/add-tags)})

(defn doc-seq
  "Given a suitable (quasi)XML file generated from a Wikipedia dump, returns a lazy sequence of maps containing sources meta-information and parsed paragraphs."
  [filename]
  (let [lines (line-seq (io/reader filename))]
    (sequence (comp (partition-by is-open?)
                    (partition-all 2)
                    (map process-doc))
              lines)))

;; This is a no-op with Wikipedia, as the metadata is streamed with the documents.
(defmethod corpus/metadata :corpus/wikipedia [_])

(defmethod corpus/files :corpus/wikipedia
  [{:keys [corpus-dir]}]
  (into #{} (fs/walk-path corpus-dir "xml")))

(defmethod corpus/documents :corpus/wikipedia
  [{:keys [files]}]
  (doc-seq (first files)))