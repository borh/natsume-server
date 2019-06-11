(ns natsume-server.nlp.importers.livedoor-test
  (:require [natsume-server.nlp.importers.livedoor :refer :all]
            [clojure.test :refer :all]
            [clojure.spec.test.alpha :as stest]
            [orchestra.spec.test :as st]
            [clojure.spec.alpha :as s]
            [natsume-server.models.corpus-specs :as corpus]))

(st/instrument)

(stest/check (stest/enumerate-namespace 'natsume-server.nlp.importers.livedoor))

(deftest load-test
  (is (s/valid? :corpus/documents
                (corpus/documents {:corpus/type :corpus/livedoor
                                   :files (corpus/files {:corpus/type :corpus/livedoor
                                                         :corpus-dir "/home/bor/Corpora/ldcc"})}))))
