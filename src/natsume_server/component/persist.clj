(ns natsume-server.component.persist
  (:require [clojure.spec.alpha :as s]
            [natsume-server.utils.fs :as fs]
            [natsume-server.nlp.annotation-middleware :as am]
            [natsume-server.nlp.readability :as rd]
            [natsume-server.component.query :as q]
            [natsume-server.models.corpus :as corpus]
    ;; All importers must be required for corpus multimethod to work.
            [natsume-server.nlp.importers.bccwj]
            [natsume-server.nlp.importers.wikipedia]
            [natsume-server.nlp.importers.newspaper]
            [natsume-server.nlp.importers.livedoor]

            [clojure.set :as set]
            [taoensso.timbre :as timbre]
            [datoteka.core :as datoteka])
  (:import (java.nio.file Path)))

(defn dorunconc
  [f coll]
  ((comp dorun (partial pmap f)) coll))

(defn filter-on-filesystem [sources files]
  (let [file-bases (set (map fs/base-name files))
        sources-basenames (set (map :metadata/basename sources))
        basenames-missing-source (set/difference file-bases sources-basenames)
        basenames-missing-from-fs (set/difference sources-basenames file-bases)
        files-to-insert (set/difference file-bases basenames-missing-source)]
    (binding [*print-length* 10]
      (when (seq basenames-missing-source)
        (timbre/debugf "%d basenames missing from sources.tsv: (Warning: will be skipped!) %s"
                       (count basenames-missing-source)
                       basenames-missing-source))
      (when (seq basenames-missing-from-fs)
        (timbre/debugf "%d basenames in sources.tsv missing on filesystem: %s"
                       (count basenames-missing-from-fs)
                       basenames-missing-from-fs)))
    (sequence (filter (fn [file-path] (contains? files-to-insert (fs/base-name file-path))))
              files)))

(s/fdef filter-on-filesystem
  :args (s/cat :sources (s/coll-of :document/metadata) :files :corpus/files)
  :ret :corpus/files)

(defn persist-sentence!
  [text tags sources-id sentence-order-id paragraph-order-id]
  (let [tree (am/sentence->tree text)
        features (rd/sentence-statistics text tree)
        sentences-id
        (-> (q/insert-sentence (assoc features
                                 :tags (into #{} (map name tags))
                                 :paragraph-order-id paragraph-order-id
                                 :sentence-order-id sentence-order-id
                                 :sources-id sources-id))
            :sentences/id)]
    (when-let [collocations (seq (:collocations features))]
      (q/insert-collocations! collocations sentences-id))
    (q/insert-tokens! (flatten (map :chunk/tokens tree)) sentences-id)
    (q/insert-unigrams! (:unigrams features) sentences-id)))

(defn persist-paragraphs! [paragraphs sources-id]
  (loop [paragraphs* paragraphs
         sentence-start-id 1                                ; ids start with 1 (same as SQL pkeys)
         paragraph-order-id 1]
    (when-let [paragraph (first paragraphs*)]
      (let [{:keys [paragraph/sentences paragraph/tags]} paragraph
            sentence-count (count sentences)
            sentence-end-id (+ sentence-start-id sentence-count)]
        ((comp dorun map)
         (fn [text sentence-order-id]
           (persist-sentence! text tags sources-id sentence-order-id paragraph-order-id))
         sentences
         (range sentence-start-id sentence-end-id))
        (recur (next paragraphs*)
               sentence-end-id
               (inc paragraph-order-id))))))

(defn persist-doc! [doc]
  ;; If metadata is in doc, we opt to persist it first, so we can get the db id later.
  (when-let [m (:document/metadata doc)]
    (q/insert-source! m))
  (let [paragraphs (:document/paragraphs doc)
        basename (or (-> doc :document/metadata :metadata/basename)
                     (-> doc :document/basename))
        sources-id (q/basename->source-id basename)]
    (when-not sources-id
      (throw (Exception. (str basename " not in: (?) " doc))))
    (persist-paragraphs! paragraphs sources-id)))

(defn infer-corpus-type [corpus-dir]
  (condp re-seq (.toString ^Path (datoteka/name corpus-dir))
    #"(?i)wiki" :corpus/wikipedia
    #"(?i)(newspaper|mai|yomi)" :corpus/newspaper
    #"(?i)(LB|OB|OC|OL|OM|OP|OT|OV|OW|OY|PB|PM|PN)" :corpus/bccwj
    #"(?i)ldcc" :corpus/livedoor
    :default))

(defn persist-corpus!
  [sampling-options corpus-dir]
  (let [corpus-type (infer-corpus-type corpus-dir)
        files (set (corpus/files {:corpus/type corpus-type :corpus-dir corpus-dir}))
        ratio (:ratio sampling-options)
        sampler (let [N (case corpus-type
                          :corpus/wikipedia 1000000
                          :corpus/newspaper 10000
                          (count files))]
                  (if (not= ratio 0.0)
                    ;; We add one to make sure at least one document is sampled.
                    (fs/sample (assoc sampling-options :total N)) #_(take (inc (int (* ratio N))))
                    identity))
        sources-metadata (corpus/metadata {:corpus/type corpus-type :corpus-dir corpus-dir})]

    ;; Insert all metadata in one go when present as separate files.
    (when sources-metadata
      (let [target-files (into #{} (sampler (filter-on-filesystem sources-metadata files)))
            target-basenames (into #{} (map fs/base-name) target-files)
            sources-filtered (into []
                                   (filter (fn [{:keys [metadata/basename]}]
                                             (contains? target-basenames basename)))
                                   sources-metadata)]
        (timbre/info "Persisting metadata for" (name corpus-type) "using" (count target-files) "/" (count files) "documents and ratio" ratio)
        (assert (= (count target-files) (count sources-filtered)))
        (q/insert-sources! sources-filtered #_(into #{} (map fs/base-name) target-files))
        (timbre/info "Persisting" (count target-files) "documents for" (name corpus-type))
        (dorunconc persist-doc! (corpus/documents {:corpus/type corpus-type :files target-files}))))

    ;; Otherwise persist per-document.
    (when-not sources-metadata
      (if (= ratio 0.0)
        (timbre/info "Persisting documents for corpus type" (name corpus-type))
        (timbre/info "Persisting documents with sampling ratio of" ratio "for" (name corpus-type)))
      (dorunconc persist-doc! (sampler (corpus/documents {:corpus/type corpus-type :files files}))))))