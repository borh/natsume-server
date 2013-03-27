(ns natsume-server.collocations
  (:require [clojure.string :as string])
  (:import [com.ibm.icu.text Transliterator Normalizer]))

;; ## Collocation extraction

(defn- get-target [t source target]
  (cond (= source (dec (count t))) nil
        (= -1 target) (nth t (dec (count t)))
        :else (nth t target)))

(defn- keyword-combine [& ks]
  (keyword (string/join "-" (map name ks))))

(defn- extract-head-tail-keys [m]
  (select-keys m [:head-string :head-pos :head-tags :head-begin :head-end
                  :tail-string :tail-pos :tail-tags :tail-begin :tail-end]))

(defn- detect-collocation
  "TODO only picks up tuples or triplets, should expand to arbitrary lengths (when it makes sense to).
   FIXME doesn't correctly handle the case of multple occurences of the same POS (kind of fixed).
   FIXME generalize to operate on chunk seq and filter for known good combinations
   FIXME FIXME FIXME rather, keep the order of chunks by putting them in a vector, as it helps with ambiguity; also make it more generic -- waaay to complicated now for what should be relatively simple -- the reason is that we are trying to fit this into the SQL/korma insert module."
  [a b]
  (let [head-keys [:head-string :head-pos :head-tags :head-begin :head-end]
        tail-keys [:tail-string :tail-pos :tail-tags :tail-begin :tail-end]
        type-data (filterv #(every? (complement nil?) (vals %))
                           [(select-keys a head-keys)
                            (select-keys a tail-keys)
                            (select-keys b head-keys)
                            (select-keys b tail-keys)])]
    {:type (mapv #(or (:head-pos %) (:tail-pos %)) type-data)
     :data type-data}))

(defn extract-collocations [tree]
  (reduce
   (fn [coll chunk]
     (let [source-id (:id chunk)
           target-id (:link chunk)
           target-chunk (get-target tree source-id target-id)]
       (if target-chunk
         (if-let [collocation (detect-collocation (extract-head-tail-keys chunk)
                                                  (extract-head-tail-keys target-chunk))]
           (conj coll collocation))
         coll)))
   []
   tree))
