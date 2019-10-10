(ns natsume-server.component.transit-test
  (:require [clojure.test :refer :all]
            [natsume-server.component.transit :refer :all]))

(deftest process-request-test
  (is (< 10000 (:count (process-request :sources/genre :tokens)))))
