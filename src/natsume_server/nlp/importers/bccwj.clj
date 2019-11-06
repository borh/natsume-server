(ns natsume-server.nlp.importers.bccwj
  (:require [datoteka.core :as d :refer [ext]]
            [natsume-server.models.corpus :as corpus]
            [natsume-server.utils.fs :as fs]
            [corpus-utils.bccwj :as bccwj-utils]))

;; # Importer for BCCWJ-Formatted C-XML data wrapping the bccwj namespace in corpus-utils.

(defmethod corpus/metadata :corpus/bccwj [_])

(defmethod corpus/files :corpus/bccwj
  [{:keys [corpus-dir]}]
  (mapcat corpus-utils.utils/zipfile-cached-files (map #(.toFile %) (fs/walk-path corpus-dir "zip"))))

(defmethod corpus/documents :corpus/bccwj
  [{:keys [files corpus-dir]}]
  (let [find-metadata-dir (fn find-docs [f]
                            (let [doc-dir (d/join f "DOC")]
                              (if (d/exists? doc-dir)
                                doc-dir
                                (recur (d/parent f)))))
        metadata-dir (find-metadata-dir corpus-dir)]
    (bccwj-utils/document-seq {:metadata-dir (.toFile metadata-dir)
                               :corpus-dir   (.toFile corpus-dir)})))
