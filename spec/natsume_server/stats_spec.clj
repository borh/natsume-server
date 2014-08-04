(ns natsume-server.stats-spec
  (:require [speclj.core :refer :all]
            ;;[speclj.run.standard :refer [run-specs]]
            [natsume-server.stats :refer :all]))

(describe "t-score"
  (it "matches results in Manning & Schutze p. 166, Table 5.6: first-made"
      (should= 2.3714145084571085
               (t {:f-ii 20 :f-ix 14907 :f-xi 9017 :f-xx 14307668}))))

(describe "chi-sq"
  (it "matches results in Manning & Schutze p. 169-170, Table 5.8: new-companies"
      (should= 1.54886920672822 ;; 366064403815991984357/236343005739815997000
               ;; :f-ox (+ 15820 14287173) :f-xo (+ 4667 14287173) :f-xi (+ 8 15820) :f-ix (+ 8 4667)
               (chi-sq {:f-ii 8 :f-oo 14287173 :f-io 4667 :f-oi 15820 :f-xx 14307668})))
  (it "matches results in Manning & Schutze p. 179, Table 5.15: house-chambre"
      (should= 553609.5741506686
               (chi-sq (expand-contingency-table {:f-ii 31950 :f-io 4793 :f-oi 12004 :f-oo 848330})))))

(describe "MI-Score"
  (it "matches results in Manning & Schutze p. 179, Table 5.15: house-chambre"
      (should= 4.1378682436101615
               (mi (expand-contingency-table {:f-ii 31950 :f-io 4793 :f-oi 12004 :f-oo 848330}))))
  (it "matches results in Manning & Schutze p. 178, Table 5.14: Agatha-Christie"
      (should= 16.314957990954188
               (mi (expand-contingency-table {:f-ii 20 :f-ix 30 :f-xi 117 :f-xx 14307668})))))

(describe "log-likelihood"
  (it "matches results in Manning & Schutze p. 174, Table 5.12: most-powerful"
      (should= 1291.42
               (llr (expand-contingency-table {:f-ii 150 :f-ix 12593 :f-xi 932 :f-xx 14307668}))))
  (it "matches results in Manning & Schutze p. 174, Table 5.12: powerful-chip"
      (should= 43.10
               (llr (expand-contingency-table {:f-ii 5 :f-ix 932 :f-xi 396 :f-xx 14307668})))))

(run-specs)
