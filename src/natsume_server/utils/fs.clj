(ns natsume-server.utils.fs
  (:require [datoteka.core :as fs]
            [bigml.sampling.simple :as sampling]))

(defn base-name [s]
  (-> s
      (fs/path)
      (fs/name)
      (fs/split-ext)
      (first)))

(defn parent-name [filename]
  (-> (fs/path filename)
      (fs/parent)
      (fs/name)))

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

(defn sample
  ([m] (partial sample m))
  ([{:keys [ratio seed replace total]
     :or   {total   (count xs)
            replace false
            seed    42
            ratio   0.1}}
    xs]
   (take (inc (int (* ratio total)))
         (sampling/sample xs :seed seed :replace replace))))