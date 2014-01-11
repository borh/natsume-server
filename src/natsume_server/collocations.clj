(ns natsume-server.collocations
  (:require [clojure.string :as string]
            [clojure.core.reducers :as r]))

;; ## Collocation extraction

(defn- get-target
  ([t source target]
     (cond (= source (dec (count t))) nil
           (= -1 target) nil ;; (nth t (dec (count t)))
           :else (nth t target)))
  ([t coll]
     (get-target t (:id coll) (:link coll))))

(comment
  (defn- keyword-combine [& ks]
    (keyword (string/join "-" (map name ks)))))

(def head-keys [:head-string :head-pos :head-tags :head-begin :head-end])
(def tail-keys [:tail-string :tail-pos :tail-tags :tail-begin :tail-end])

(comment
  (defn- extract-head-tail-keys [m]
    (select-keys m (concat head-keys tail-keys)))

  (defn- detect-collocation
    "TODO only picks up tuples or triplets, should expand to arbitrary lengths (when it makes sense to).
   FIXME doesn't correctly handle the case of multiple occurences of the same POS (kind of fixed).
   FIXME generalize to operate on chunk seq and filter for known good combinations
   FIXME FIXME FIXME rather, keep the order of chunks by putting them in a vector, as it helps with ambiguity; also make it more generic -- waaay to complicated now for what should be relatively simple -- the reason is that we are trying to fit this into the SQL/korma insert module."
    [a b]
    (let [type-data (filterv #(every? (complement nil?) (vals %))
                             [(select-keys a head-keys)
                              (select-keys a tail-keys)
                              (select-keys b head-keys)
                              (select-keys b tail-keys)])]
      {:type (mapv #(or (:head-pos %) (:tail-pos %)) type-data)
       :data type-data})))

(defn- follow-link
  "Given a tree (coll), a chunk in that tree, and a path through the tree, recurs until the end of the link path and returns it."
  [tree chunk path max-order]
  (loop [chunk* chunk
         path* path]
    ;;(print "|" (:head-string chunk*) ":" (count path*))
    (if (> (count path*) max-order)
      path*
      (if-let [target (get-target tree chunk*)]
        (recur target
               (conj path* target))
        path*))))

(comment
  (defn- quality-check [path]
    (->> path
         (r/take-while (fn [chunk] (every? (complement nil?) (vals (select-keys chunk (concat head-keys tail-keys))))))
         (into []))))

(defn- make-collocation [path]
  {:type (mapv #(or (:head-pos %) (:tail-pos %)) path)
   :tags (->> path (r/map (fn [chunk] (clojure.set/union (:head-tags chunk) (:tail-tags chunk)))) (r/reduce clojure.set/union) (r/remove nil?) (into #{}))
   :data path})

(defn extract-collocations
  "Given a tree (coll) and maximum n-gram order, extracts all linked chunks from coll matching given constraints."
  ([tree max-order]
     (->> tree
          (r/map (fn [chunk]
                   (->> (follow-link tree chunk [chunk] max-order)
                        (r/mapcat (fn [chunk]
                                    (->> [(select-keys chunk head-keys)
                                          (select-keys chunk tail-keys)]
                                         (r/filter (fn [part] (every? (complement nil?) (vals part))))
                                         (into []))))
                        (into []))))
          (r/reduce (fn [coll path]
                      (into coll
                            (for [n (range (let [order (count path)]
                                             (if (> order max-order) max-order order)))]
                              (make-collocation (subvec path 0 (inc n))))))
                    [])))
  ([tree] (extract-collocations tree 4)))

(comment
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
     tree)))
