(ns natsume-server.core-spec
  (:use [speclj.core]
        [natsume-server.core]))

(comment
  (time (doall (apply -main "-v" "-c" (for [dir ["LB" "OM" "OT" "OV" "OY" "wikipedia"]] (str "/work/projects/github/natsume-server/local/perf-testdata/" dir)))))
  (time (doall (-main "-v" "-c" "--sampling-ratio" "0.001" "/data/Natsume-Corpora/Wikipedia-Articles/Processed")))
  (time (doall (apply -main "-v" "-c" "--search" "--sampling-ratio" "0.001" (for [dir ["LB" "OB" "OC" "OL" "OM" "OP" "OT" "OV" "OW" "OY" "PB" "PM" "PN"]] (str "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/" dir)))))
  (time (doall (apply -main "-v" "-c" (for [dir ["OV"]] (str "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/" dir)))))
  (time (doall (apply -main "-v" "-c" (for [dir ["JStage-環境資源工学" "JStage-電気学会論文誌" "JStage-日本化学会誌" "JStage-土木学会論文集D" "JStage-土木学会論文集C" "JStage-土木学会論文集B" "JStage-土木学会論文集A" "JStage-日本医科大学医学会雑誌" "JNLP-Journal"]] (str "/data/Natsume-Corpora/" dir)))))

  (let [STJC (for [dir ["JStage-環境資源工学" "JStage-電気学会論文誌" "JStage-日本化学会誌" "JStage-土木学会論文集D" "JStage-土木学会論文集C" "JStage-土木学会論文集B" "JStage-土木学会論文集A" "JStage-日本医科大学医学会雑誌" "JNLP-Journal" "nlp_annual_txt"]] (str "/data/Natsume-Corpora/" dir))
        BCCWJ (for [dir ["LB" "OB" "OC" "OL" "OM" "OP" "OT" "OV" "OW" "OY" "PB" "PM" "PN"]] (str "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/" dir))
        Wikipedia ["/data/Natsume-Corpora/Wikipedia-Articles/Processed"]]
    (time (e! (apply -main "-v" "-c" "--search" "--sampling-ratio" "0.001" (concat STJC BCCWJ Wikipedia)))))

  (let [STJC (for [dir ["JStage-環境資源工学" "JStage-電気学会論文誌" "JStage-日本化学会誌" "JStage-土木学会論文集D" "JStage-土木学会論文集C" "JStage-土木学会論文集B" "JStage-土木学会論文集A" "JStage-日本医科大学医学会雑誌" "JNLP-Journal" "nlp_annual_txt"]] (str "/media/ssd-fast/corpora/STJC/" dir))
        BCCWJ (for [dir ["LB" "OB" "OC" "OL" "OM" "OP" "OT" "OV" "OW" "OY" "PB" "PM" "PN"]] (str "/media/ssd-fast/corpora/BCCWJ-2012-dvd1/C-XML/VARIABLE/" dir))
        Wikipedia ["/media/ssd-fast/corpora/Wikipedia/Processed"]]
    (time (e! (apply -main "-v" "-c" "--search" (concat STJC BCCWJ)))))

  (time (doall (-main "-v" "/data/Natsume-Corpora/JStage-土木学会論文集A")))
  (time (doall (apply -main "-v" "-s" (for [dir ["OV"]] (str "/work/projects/github/natsume-server/local/perf-testdata/" dir)))))
  (time (doall (-main "-v" "-s" "--no-process")))
  )
