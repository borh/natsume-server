(ns natsume-server.models.corpus
  (:require [natsume-server.models.corpus-specs :refer :all]
            [natsume-server.utils.fs :as fs]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [natsume-server.nlp.text :as text]))

(def dispatch-key :corpus/type)

(defmulti metadata dispatch-key)
(defmethod metadata :default
  [{:keys [corpus-dir] :as m}]
  (map
    (fn [[title author year basename genres-name subgenres-name
          subsubgenres-name subsubsubgenres-name permission]]
      #:metadata{:title      title
                 :author     author
                 :year       (Integer/parseInt year)
                 :basename   basename
                 :genre      (reduce (fn                    ;; Remove repeated categories.
                                       ([] [])
                                       ([a b] (if (= (peek a) b) a (conj a b))))
                                     [genres-name]
                                     [subgenres-name subsubgenres-name subsubsubgenres-name])
                 :permission (if (re-seq #"(?i)true|false" permission)
                               (Boolean/valueOf ^String permission)
                               (= "1" permission))})
    (with-open [sources-reader (io/reader (str corpus-dir "/sources.tsv"))]
      (doall (csv/read-csv sources-reader :separator \tab :quote 0)))))

(defmulti files dispatch-key)
(defmethod files :default
  [{:keys [corpus-dir]}]
  (into #{} (fs/walk-path corpus-dir "txt")))

(defmulti document dispatch-key)
(defmethod document :default
  [{:keys [file]}]
  {:document/paragraphs (text/file->doc file)
   :document/basename   (fs/base-name file)})

(defmulti documents dispatch-key)
(defmethod documents :default
  [{:keys [files corpus-type]}]
  (map #(document {:corpus-type corpus-type :file %}) files))




