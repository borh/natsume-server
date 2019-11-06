(ns natsume-server.component.database-test
  (:require [clojure.test :refer :all]
            [natsume-server.component.database :refer :all]))

(use-fixtures :once (fn [f] (mount.core/start) (f)))

(deftest db-test
  (testing "FIXME"
    (is connection)))
