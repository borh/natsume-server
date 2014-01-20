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

(def head-keys [:head-string :head-pos :head-tags :head-begin :head-end])
(def tail-keys [:tail-string :tail-pos :tail-tags :tail-begin :tail-end])

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
