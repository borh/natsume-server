(ns natsume-server.nlp.importers.wikipedia
  (:require [corpus-utils.wikipedia :as utils-wikipedia]
            [natsume-server.models.corpus :as corpus]
            [natsume-server.utils.fs :as fs]))

;; This is a no-op with Wikipedia, as the metadata is streamed with the documents.
(defmethod corpus/metadata :corpus/wikipedia [_])

(defmethod corpus/files :corpus/wikipedia
  [{:keys [corpus-dir]}]
  #_(into #{} (map #(.toFile %)) (fs/walk-path corpus-dir "xz")))

(defmethod corpus/documents :corpus/wikipedia
  [{:keys [corpus-dir]}]
  (utils-wikipedia/document-seq {:corpus-dir corpus-dir}))