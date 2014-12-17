(ns natsume-server.endpoint.api-test
  (:require [clojure.test :refer :all]
            [natsume-server.endpoint.api :as api]))

(comment
  (def handler
    (api/api-endpoint {})))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))
