(ns natsume-server.utils.numbers
  (:import [java.text DecimalFormat]))

(def ^:private decimal-format (DecimalFormat. "#.00"))
(defprotocol ICompactNumber
  (compact-number [num]))
(extend-protocol ICompactNumber

  java.lang.Double
  (compact-number [x]
    (Double/parseDouble (.format ^DecimalFormat decimal-format x)))

  clojure.lang.Ratio
  (compact-number [x]
    (Double/parseDouble (.format ^DecimalFormat decimal-format x)))

  java.lang.Long
  (compact-number [x] x)

  clojure.lang.BigInt
  (compact-number [x] x))