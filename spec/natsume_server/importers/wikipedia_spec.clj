(ns natsume-server.importers.wikipedia-spec
  (:require [natsume-server.importers.wikipedia :refer :all]
            [speclj.core :refer :all]))

(describe "Wikipedia importer"
  (it "extracts header information"
      (should= ["477428" "アルラレッドAC"]
               (extract-header "<doc id=\"477428\" url=\"http://ja.wikipedia.org/wiki/?curid=477428\" title=\"アルラレッドAC\">")))
  )
