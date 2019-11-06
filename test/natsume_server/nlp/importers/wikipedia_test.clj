(ns natsume-server.nlp.importers.wikipedia-test
  (:require [natsume-server.nlp.importers.wikipedia :refer :all]
            [clojure.test :refer :all]
            [clojure.spec.test.alpha :as stest]
            [orchestra.spec.test :as st]
            [clojure.spec.alpha :as s]
            [natsume-server.models.corpus :as corpus]))

(st/instrument)

(stest/check (stest/enumerate-namespace 'natsume-server.nlp.importers.wikipedia))

(deftest load-test
  (testing "Wikipedia corpus dump file exists and only one present"
    ;; Wikipedia is quite large, so we only test a subset.
    (testing "Schema check for first 10 Wikipedia documents"
      (doseq [doc (take 10 (corpus/documents
                             {:corpus/type :corpus/wikipedia
                              :corpus-dir  "/home/bor/Corpora/Wikipedia/Ja/jawiki-20191001-pages-articles-wikiextractor-categories.xml.xz"}))]
        (is (s/valid? :corpus/document doc))))))