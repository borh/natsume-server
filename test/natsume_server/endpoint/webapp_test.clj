(ns natsume-server.endpoint.webapp-test
  (:require [clojure.test :refer :all]
            [natsume-server.endpoint.webapp :as webapp]))

(comment
  (def handler
    (webapp/webapp-endpoint {})))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))
