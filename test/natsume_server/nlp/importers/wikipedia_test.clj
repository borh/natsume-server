(ns natsume-server.nlp.importers.wikipedia-test
  (:require [natsume-server.nlp.importers.wikipedia :refer :all]
            [clojure.test :refer :all]
            [clojure.spec.test.alpha :as stest]
            [orchestra.spec.test :as st]
            [clojure.spec.alpha :as s]
            [natsume-server.models.corpus-specs :as corpus]))

(st/instrument)

(stest/check (stest/enumerate-namespace 'natsume-server.nlp.importers.wikipedia))

(deftest load-test
  (testing "Wikipedia corpus dump file exists and only one present"
    (let [files (corpus/files {:corpus/type :corpus/wikipedia
                               :corpus-dir  "/home/bor/Projects/natsume-server/jawiki"})]
      (is (s/valid? :corpus/files files))
      (is (= 1 (count files)))))
  ;; Wikipedia is quite large, so we only test a subset.
  (testing "Schema check for first 10 Wikipedia documents"
    (doseq [doc (take 10 (corpus/documents
                           {:corpus/type :corpus/wikipedia
                            :files       (corpus/files
                                           {:corpus/type :corpus/wikipedia
                                            :corpus-dir  "/home/bor/Projects/natsume-server/jawiki"})}))]
      (is (s/valid? :corpus/document doc)))))
