(ns natsume-server.models.tree
  (:require [clojure.zip :as z] ;; TODO: try https://github.com/akhudek/fast-zip
            [plumbing.core :refer [?> for-map]]))

;; # Genre tree creation
;;
;; Output format follows the d3 tree example: https://github.com/mbostock/d3/wiki/Tree-Layout
;;
;; ```json
;; {
;;  "name": "flare",
;;  "children": [
;;   {
;;    "name": "analytics",
;;    "children": [
;;     {
;;      "name": "cluster",
;;      "children": [
;;       {"name": "AgglomerativeCluster", "size": 3938},
;;       {"name": "CommunityStructure", "size": 3812},
;;       {"name": "MergeEdge", "size": 743}
;;      ]
;;     },
;;     {
;;      "name": "graph",
;;      "children": [
;;       {"name": "BetweennessCentrality", "size": 3534},
;;       {"name": "LinkDistance", "size": 5731}
;;      ]
;;     }
;;    ]
;;   }
;;  ]
;; }
;; ```

(defn- tree-zipper [root]
  (letfn [(make-node [node children]
            (with-meta (assoc node :children (vec children)) (meta node)))]
    (z/zipper map? :children make-node root)))

(defn- root-loc
  "zips all the way up and returns the root loc, reflecting any
  changes. Modified from clojure.zip/root"
  [loc]
  (if (= :end (loc 1))
    loc
    (let [p (z/up loc)]
      (if p
        (recur p)
        loc))))
(defn- find-index [field-name root]
  (if-let [children (:children root)]
    (if-let [[index _]
             (->> children ; Linear search (but n is small).
                  (map-indexed vector)
                  (some #(and (= (:name (second %)) field-name) %)))]
      index)))
(defn- find-loc [field-name root]
  (loop [loc root]
    (if loc
      (if (= field-name (-> loc z/node :name))
        loc
        (recur (z/right loc))))))

(defn- update-keys [m1 m2 ks f]
  (merge m1
         (merge-with f
                     (select-keys m1 ks)
                     (select-keys m2 ks))))

(defn seq-to-tree
  "Transforms a vector of hierarchies (just another vector) into a tree data structure suitable for export to JavaScript.

  The underlying algorithm utilizes a custom tree zipper function.
  One downside to using zippers here is that searching for the child nodes is linear, but since the tree is heavily branched, this should not pose a problem even with considerable data.
  TODO: sentence-level features Q/A, conversational, etc?"
  [hierarchies & {:keys [merge-keys merge-fn root-name root-values]
                  :or {merge-keys [:count]
                       merge-fn +
                       root-name "Genres"}}] ; -> [[:a :b :c :d] [:a :b :x :z] ... ]
  (z/root ; Return final tree.
   (reduce ; Reduce over hierarchies.
    (fn [tree m]
      (->> (:genre m)
           (reduce ; Reduce over fields in hierarchy (:a, :b, ...).
            (fn [loc field]
              (if-let [found-loc (if-let [child-loc (z/down loc)] (find-loc field child-loc))]
                (z/edit found-loc update-keys m merge-keys merge-fn) ; Node already exists: update counts in node.
                (-> loc ; Add new node and move loc to it (insert-child is a prepend op).
                    (z/insert-child (merge {:name field}
                                           (select-keys m merge-keys)))
                    z/down)))
            tree)
           root-loc)) ; Move to root location.
    (tree-zipper (merge {:name root-name}
                        (or root-values
                            (for-map [k merge-keys]
                                     k (reduce merge-fn (map k hierarchies)))))) ;; FIXME: seems this is overridden???
    hierarchies)))

(defn- tree-path [tree-loc]
  (loop [path '()
         loc tree-loc]
    (if loc
      (recur
       (conj path (-> loc z/node :name))
       (z/up loc))
      path)))

(defn- get-count-in-tree [field loc ks]
  (loop [node-names ks
         t-loc loc
         count 0]
    (if-not node-names ; synthread?
      count
      (if t-loc
        (if-let [found-loc (find-loc (first node-names) t-loc)]
          (recur (next node-names)
                 (z/down found-loc)
                 (-> found-loc z/node field int)))))))

(defn normalize-tree
  "Given a genre tree in-tree, returns normalized counts by genre, which are passed in as norm-tree.
  The type of normalized counts returned depend on in-tree, while norm-tree can use a number of counts: document, paragraph and sentence."
  [norm-tree in-tree & {:keys [update-field boost-factor clean-up-fn]
                        :or {update-field :count boost-factor 1000000}}]
  (let [in-tree-zipper (tree-zipper in-tree)
        norm-tree-zipper (tree-zipper norm-tree)]
    (loop [loc in-tree-zipper]
      (if (z/end? loc)
        (z/root loc)
        (recur
         (z/next ; NOTE: get-count-in-tree should always return a positive number.
          (z/edit loc update-in [update-field] #(-> (/ % (get-count-in-tree update-field norm-tree-zipper (tree-path loc)) (/ 1 boost-factor))
                                                    (?> clean-up-fn clean-up-fn)))))))))
