(ns natsume-server.core-spec
  (:use [speclj.core]
        [natsume-server.core]))

(time (doall (apply -main (for [dir ["LB" "OM" "OT" "OV" "OY" "wikipedia"]] (str "/work/projects/github/natsume-server/local/perf-testdata/" dir)))))
