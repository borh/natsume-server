(ns natsume-server.nlp.collocations
  (:require [clojure.core.reducers :as r]
            [schema.core :as s]
            [clojure.set :as set]))

;; ## Collocation extraction

(defn- get-target
  ([t source target]
   (cond (= source (dec (count t))) nil
         (= -1 target) nil                                  ;; (nth t (dec (count t)))
         (>= target (count t)) nil
         :else (nth t target)))
  ([t coll]
   (get-target t (:chunk/id coll) (:chunk/link coll))))

(def head-keys [:chunk/head-string :chunk/head-pos :chunk/head-tags :chunk/head-begin :chunk/head-end])
(def tail-keys [:chunk/tail-string :chunk/tail-pos :chunk/tail-tags :chunk/tail-begin :chunk/tail-end])

(defn- follow-link
  "Given a tree (coll), a chunk in that tree, and a path through the tree, recurs until the end of the link path and returns it."
  [tree chunk path max-order]
  (loop [chunk* chunk
         path* path]
    (if (> (count path*) max-order)
      path*
      (if-let [target (get-target tree chunk*)]
        (recur target
               (conj path* target))
        path*))))

;; FIXME/TODO add idiom tags to **n-grams** as :idiom using http://openmwe.sourceforge.jp/pukiwiki-j/index.php?Idioms -> data/DB.all.id.20070528.xz
;; Use EDR cdrom

(defn- make-collocation [path]
  {:type (mapv #(or (:chunk/head-pos %) (:chunk/tail-pos %)) path)
   :tags (->> path
              (r/map (fn [chunk]
                       (set/union (:chunk/head-tags chunk) (:chunk/tail-tags chunk))))
              (r/reduce set/union)
              (r/remove nil?)
              (into #{}))                                   ;; FIXME add :idiom (see above)
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

(s/defn extract-unigrams :- [{:string (s/conditional not-empty s/Str)
                              :pos    s/Keyword
                              :tags   #{s/Keyword}}]
  [tree :- [s/Any #_ChunkSchema]]
  (->> tree
       (r/mapcat
         (fn [chunk]
           [{:string (:chunk/head-string chunk)
             :orth   (:chunk/head-orth chunk)
             :pos    (:chunk/head-pos chunk)
             :tags   (:chunk/head-tags chunk)}
            {:string (:chunk/tail-string chunk)
             :orth   (:chunk/tail-orth chunk)
             :pos    (:chunk/tail-pos chunk)
             :tags   (:chunk/tail-tags chunk)}]))
       (r/filter (fn [unigram]
                   (not-any? nil? ((juxt :string :orth :pos) unigram))))
       (into [])))
