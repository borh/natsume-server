(ns natsume-server.models.db-spec
  (:require [speclj.core :refer :all]
            [d3-compat-tree.tree :refer [normalize-tree]]
            [natsume-server.models.db :refer :all]))

;; Generate all escape sequences.
;; This is meant more for checking if the sentences are correctly escaped to be inserted into postgres.
(def escape-sequences
  (map char (range 128)))

(describe "Tree"
  (it "normalizes with itself"
      (should (clojure.walk/postwalk #(if-not (map? %) % (if (= 1 (:count %)) true false))
                                     (normalize-tree (genres->tree) (genres->tree) :boost-factor 1)))))
