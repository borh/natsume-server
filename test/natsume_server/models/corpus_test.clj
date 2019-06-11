(ns natsume-server.models.corpus-test
  (:require [clojure.test :refer :all]
            [clojure.spec.test.alpha :as stest]
            [orchestra.spec.test :as st]
            [clojure.spec.alpha :as s]
            [natsume-server.models.corpus-specs :refer :all]))

(st/instrument)

(stest/check (stest/enumerate-namespace 'natsume-server.models.corpus-specs))

(deftest corpus-spec-test
  (is (s/exercise :corpus/documents)))