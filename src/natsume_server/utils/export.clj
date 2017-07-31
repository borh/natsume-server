(ns natsume-server.utils.export
  (:require [clojure.spec.alpha :as s]
            [dk.ative.docjure.spreadsheet :as spreadsheet]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

(s/def :filename/string string?)

(s/fdef ::sha256
  :args :filename/string
  :ret #(s/valid? (string? %)))

(defn sha256 [s]
  (let [hash (MessageDigest/getInstance "SHA-256")]
    (. hash update (.getBytes s))
    (let [digest (.digest hash)]
      (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

(defn save-spreadsheet! [filename title data headers]
  (let [wb (spreadsheet/create-workbook
            title (reduce
                   (fn [a m]
                     (conj a (for [h headers]
                               (let [v (get m h)]
                                 (if (not (or (string? v) (number? v)))
                                   (str/join "," (map name v))
                                   v)))))
                   [(mapv name headers)]
                   data))
        sheet (spreadsheet/select-sheet title wb)
        header-row (first (spreadsheet/row-seq sheet))]
    (spreadsheet/set-row-style! header-row (spreadsheet/create-cell-style! wb {:font {:bold true}}))
    (spreadsheet/save-workbook! filename wb)))
