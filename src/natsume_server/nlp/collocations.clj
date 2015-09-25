(ns natsume-server.nlp.collocations
  (:require [clojure.core.reducers :as r]
            [schema.core :as s]
            [natsume-server.nlp.cabocha-wrapper :refer [ChunkSchema]]))

;; ## Collocation extraction

(defn- get-target
  ([t source target]
     ;;(println "source " source " target " target)
     (cond (= source (dec (count t))) nil
           (= -1 target) nil ;; (nth t (dec (count t)))
           (>= target (count t)) nil
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

;; FIXME/TODO add idiom tags to **n-grams** as :idiom using http://openmwe.sourceforge.jp/pukiwiki-j/index.php?Idioms -> data/DB.all.id.20070528.xz
;; Use EDR cdrom

(defn- make-collocation [path]
  {:type (mapv #(or (:head-pos %) (:tail-pos %)) path)
   :tags (->> path (r/map (fn [chunk] (clojure.set/union (:head-tags chunk) (:tail-tags chunk)))) (r/reduce clojure.set/union) (r/remove nil?) (into #{})) ;; FIXME add :idiom (see above)
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
                              :pos s/Keyword
                              :tags #{s/Keyword}}]
  [tree :- [s/Any #_ChunkSchema]]
  (->> tree
       (r/mapcat
         (fn [chunk]
           [{:string (:head-string chunk)
             :pos    (:head-pos    chunk)
             :tags   (:head-tags   chunk)}
            {:string (:tail-string chunk)
             :pos    (:tail-pos    chunk)
             :tags   (:tail-tags   chunk)}]))
       (r/remove (fn [unigram]
                   #_(when (or (nil? (:string unigram)) (nil? (:pos unigram)))
                       (println unigram))
                   (or (nil? (:string unigram)) (nil? (:pos unigram)))))
       (into [])))
