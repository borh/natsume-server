(ns natsume-server.models.corpus-specs
  (:require [clojure.spec.alpha :as s])
  (:import [java.nio.file Path]))

;; Main dispatch key
(s/def :corpus/type keyword?)

(s/def :corpus/file #(instance? Path %))
(s/def :corpus/files (s/coll-of :corpus/file))

(s/def :metadata/title string?)
(s/def :metadata/author string?)
(s/def :metadata/year int?)
(s/def :metadata/basename string?)
(s/def :metadata/genre (s/coll-of string?))
(s/def :metadata/permission boolean?)

(s/def :document/metadata
  (s/keys :req
          [:metadata/title :metadata/author :metadata/year :metadata/basename
           :metadata/genre :metadata/permission]
          :opt [:metadata/category]))

(s/def :sentence/text string?)
(s/def :paragraph/sentences (s/coll-of :sentence/text))
(s/def :paragraph/tags (s/coll-of keyword?))
(s/def :document/paragraph (s/keys :req [:paragraph/tags :paragraph/sentences]))
(s/def :document/paragraphs (s/coll-of :document/paragraph))
(s/def :corpus/document (s/keys :req [:document/paragraphs]
                                ;; FIXME Optional :basename at top level for documents with external metadata.
                                :opt [:document/metadata :document/basename]))
(s/def :corpus/documents (s/coll-of :corpus/document))
