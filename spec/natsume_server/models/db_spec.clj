(ns natsume-server.models.db-spec
  (:require [speclj.core :refer :all]
            [natsume-server.models.db :refer :all]))

;; Generate all escape sequences.
;; This is meant more for checking if the sentences are correctly escaped to be inserted into postgres.
(def escape-sequences
  (map char (range 128)))
