(ns natsume-server.nlp.importers.bccwj
  (:require [clojure.java.io :as io]
            [clojure.xml :as xml]
            [clojure.zip :as z]
            [datoteka.core :refer [ext]]
            [natsume-server.models.corpus :as corpus]
            [natsume-server.utils.fs :as fs]
            [corpus-utils.bccwj :as bccwj-utils]
            [clojure.spec.alpha :as s]
            [fast-zip.core :as fz])
  (:import (fast_zip.core ZipperLocation)))

;; # Importer for BCCWJ-Formatted C-XML Data
;;
;; Imports C-XML-type data.
;; M-XML is also supported, but is not recommended as we do our own parsing.
;;
;; ## XML Tags
;;
;; Definition of XML tags that trigger a paragraph break.
;; Sentence breaks are triggered by the :sentence tag.
;; Refer to BCCWJ 1.0 Manual V1 Table 5.2 (pp. 78) for all tags and their meanings.
(def paragraph-level-tags
  #{:article
    :blockEnd
    :cluster
    :titleBlock
    :title
    :orphanedTitle
    :list
    :paragraph
    :verse
    :br                                                     ; Inline tag. TODO: find example where this is right or wrong.
    :speech
    :speaker                                                ; For OM.
    :caption
    :citation
    :quotation
    :OCAnswer
    :OCQuestion})

(comment                                                    ; Not used at present. The text attr should not be used as a string in the sentence.
  (def in-sentence-tags
    #{:ruby
      :correction
      :missingCharacter
      :enclosedCharacter
      :er
      :cursive
      :image                                                ;; Inline tag (Emoji).
      :noteBodyInline                                       ;; Inline tag.
      :noteMarker                                           ;; Inline tag.
      :superScript
      :subScript
      :fraction
      :delete
      :br
      :verseLine
      :info
      :rejectedSpan
      :substitution
      :quote
      :citation
      :sentence}))

;; ## XML Extraction
;;
;; XML is extracted to a tagged paragraph/sentence data structure of the form:
;;
;;     [{:tags #{:some-tag, :another-tag},
;;       :sentences ["First.", "Second sentence."]},
;;      {:tags #{ ... },
;;       :sentences [ ... ]}]

(defn- backtrack-with-distance
  "Modified from `clojure.zip/next` source. Like zip/next, but also keeps track of how far up the tree it goes."
  [loc]
  (loop [p loc
         depth 0]
    (if (z/up p)
      (or (if-let [r (z/right (z/up p))] [r (inc depth)]) (recur (z/up p) (inc depth)))
      [[(z/node p) :end] depth])))

(defn backtrack-with-distance
  "Modified from `fast-zip.core/next` source. Like zip/next, but also keeps track of how far up the tree it goes."
  [loc]
  (loop [p loc
         depth 0]
    (if (and (not (identical? :end (.path p))) (fz/up p))
      (or (if-let [r (fz/right (fz/up p))] {:loc r :depth (inc depth)}) (recur (fz/up p) (inc depth)))
      {:loc   (ZipperLocation. (.ops loc) (.node loc) :end)
       :depth depth})))

(s/def ::loc #(instance? ZipperLocation %))
(s/def ::depth int?)
(s/fdef backtrack-with-distance
  :args (s/cat :loc ::loc)
  :ret (s/keys :req-un [::loc ::depth]))

;; FIXME break into emitter and consume-sequence-and-build-sentences functions; naming: next-direction conflates direction and depth

(defn walk-and-emit
  "Traverses xml-data (parsed with clojure.xml/parse or clojure.data.xml/parse) using a zipper and incrementally builds up and returns the document as a vector of maps (representing paragraphs), each element of which contains tags and a vector of sentences."
  [paragraph-level-tags xml-data]
  (loop [xml-loc (z/xml-zip xml-data)
         tag-stack []
         par-loc (z/down (z/vector-zip [{:tags [] :sentences []}]))]
    (if (z/end? xml-loc)
      (z/root par-loc)
      (let [xml-node (z/node xml-loc)
            tag (:tag xml-node)
            xml-loc-down (and (z/branch? xml-loc) (z/down xml-loc))
            xml-loc-right (and (not xml-loc-down) (z/right xml-loc))
            xml-loc-up (and (not xml-loc-down) (not xml-loc-right) (backtrack-with-distance xml-loc)) ; At lowest depth for this paragraph.
            next-direction (cond xml-loc-down :down
                                 xml-loc-right :same
                                 :else (second xml-loc-up))
            coded-tag (if (#{:speech :speaker :title :titleBlock :orphanedTitle :list :caption :citation :quotation :OCAnswer :OCQuestion} tag)
                        (tag {:titleBlock :title :orphanedTitle :title} tag))
            new-tag-stack (case next-direction
                            :down (conj tag-stack coded-tag)
                            :same (if (= :br tag) tag-stack (conj (pop tag-stack) coded-tag)) ; :br is an exception as a paragraph break, as it does not increase XML tree depth
                            ((apply comp (repeat next-direction pop)) tag-stack))] ; Discard equal to up depth.
        (recur
          (or xml-loc-down xml-loc-right (first xml-loc-up)) ; Same as (z/next xml-loc).
          new-tag-stack
          (cond (paragraph-level-tags tag)                  ; Insert new paragraph, inserting the new tag stack.
                (-> par-loc (z/insert-right {:tags new-tag-stack :sentences [""]}) z/right)

                (= :sentence tag)                           ; Insert new sentence.
                (let [tag-stack (-> par-loc z/node :tag-stack)]
                  ;; FIXME debug tag-stack
                  (-> par-loc (z/edit update-in [:sentences] conj "")))

                (string? xml-node)                          ; Update last-inserted sentence's text.
                (-> par-loc (z/edit update-in [:sentences (-> par-loc z/node :sentences count dec)] #(str % xml-node)))

                :else par-loc))))))                         ; Do nothing.

(defn xml->paragraph-sentences
  [filename corpus]
  (->> filename
       io/input-stream
       xml/parse
       (walk-and-emit (case corpus "OY"
                                   (disj paragraph-level-tags :br)
                                   paragraph-level-tags))
       (map #(hash-map                                      ; Remove nils/empty strings from :tags and :sentences.
               :paragraph/tags (set (filter identity (:tags %)))
               :paragraph/sentences (vec (remove empty? (:sentences %)))))
       (remove #(empty? (:paragraph/sentences %)))                    ; Remove paragraphs with no sentences.
       vec))

(s/fdef xml->paragraph-sentences
  :args (s/cat :filename :corpus/file :corpus string?)
  :ret :document/paragraphs)

(defmethod corpus/files :corpus/bccwj
  [{:keys [corpus-dir]}]
  (into #{} (fs/walk-path corpus-dir "xml")))

(defn infer-subcorpus
  [filename]
  (subs (fs/base-name filename) 0 2))

(defmethod corpus/documents :corpus/bccwj
  [{:keys [files]}]
  (map (fn [filename]
         (let [subcorpus (infer-subcorpus filename)]
           {:document/paragraphs (xml->paragraph-sentences filename subcorpus)
            :document/basename (fs/base-name filename)}))
       files))

(comment                                                    ; Plain-text version (DEPRECATED).
  (defn emit-tags-flat
    [{:keys [tag] :as el}]
    (cond
      (contains? paragraph-level-tags tag) "\n\n"
      (= :sentence tag) "\n"
      (string? el) el
      :else ""))

  (defn seq->zip->text
    [xs]
    (clojure.walk/walk emit-tags-flat
                       string/join
                       xs))

  (defn xml->plain-text
    [filename]
    (clojure.string/replace
      (->> filename
           io/input-stream
           xml/parse
           xml-seq
           seq->zip->text
           s/trim)
      #"[\n]{3,}"
      "\n\n")))
