(ns natsume-server.collocations
  (:require [natsume-server.cabocha-wrapper :as cw]))

;; :pos1 形容詞 -> :display :t, :type :adjective
;; :pos1 助動詞 -> :display :f, :type :adjective

;; The final form recorded for insertion into Natsume is filtered for
;; :display, and the :lemma form is used(?)