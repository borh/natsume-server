(ns natsume-server.component.transit-test
  (:require [clojure.test :refer :all]
            [natsume-server.component.transit :refer :all]))

(use-fixtures :once (fn [f] (mount.core/start) (f)))

(deftest process-request-test
  (is (< 10000 (:count (process-request :sources/genre :tokens)))))
