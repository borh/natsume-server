;; # Text manipulation utilities
(ns natsume-server.nlp.text
  (:require [clojure.core.reducers :as r]
            [clojure.string :as string]
            [natsume-server.utils.reducers :as u]))

;; # Sentence and paragraph splitting
(def delimiter   #"[\.!\?．。！？]")
;; Take care not to use this with JStage data -- temporary hack for BCCWJ
(def delimiter-2 #"[!\?。！？]")
(def closing-quotation #"[\)）」』】］〕〉》\]]") ; TODO
(def opening-quotation #"[\(（「『［【〔〈《\[]") ; TODO
(def numbers #"[０-９\d]")
;;(def alphanumerics #"[0-9\uFF10-\uFF19a-zA-Z\uFF41-\uFF5A\uFF21-\uFF3A]")
(def alphanumerics #"[\d０-９a-zA-Zａ-ｚＡ-Ｚ]")

(def sentence-split-re
  (re-pattern
   (format "(?<=%s+)(?!%s+|%s+)"
           delimiter-2
           closing-quotation
           alphanumerics)))

(defn codepoint-range->string [codepoints]
  (string/join (for [codepoint codepoints] (char codepoint))))

(def delimiter-set (set (vec (str delimiter))))
(def alphanumerics-set (set (vec (str "0123456789"
                                      (codepoint-range->string (range 65 123))
                                      (codepoint-range->string (range 65313 65371))
                                      (codepoint-range->string (range 65296 65306))))))
(def closing-quotation-set (set (vec (str closing-quotation))))

(defn split-japanese-sentence
  "Splits given string on Japanese sentence boundaries. Returns a
  vector of sentences.

  TODO: - fail if it is '~5.~' or '~.5~'
        - fail if it is part of a word (Yahoo!)
        - fail if not all quotations have been closed"
  [s]
  (->> s
       reverse
       vec
       (r/reduce (fn
                   ([] [])
                   ([a x]
                      (let [y (peek a)
                            z (and y (peek (pop a)))]
                        (if (and y z
                                 (delimiter-set y)                   ; ...|x |y |z |...
                                 (not (or (and (alphanumerics-set x) ;|   |５|．|０|
                                               (not= \。 y)          ;|mpl|e |. |c |om/
                                               (alphanumerics-set z));|   |  |  |  |
                                          (closing-quotation-set z)  ; ...|る|。|）|と言った
                                          (delimiter-set z))))       ; ...|。|。|。|
                          (conj (pop (pop a)) z \newline y x)
                          (conj a x))))))
       reverse
       string/join
       string/split-lines))

;; TODO FIXME make a stand-off interface to the text that keeps the
;; original but always returns, on demand (memoized?), paragraphs,
;; sentences, etc. The interface is required to provide character
;; offsets for everything and convert between them.
;; This might also pave the way to quotation detection support, or
;; even integration with kytea+eda/juman+knp.

(defn lines->paragraph-sentences
  "Splits string into paragraphs and sentences.
   Paragraphs are defined as:
   1) one or more non-empty lines delimited by one empty line or BOF/EOF
   2) lines prefixed with fullwidth unicode space '　'"
  [lines]
  (->> lines
       (u/partition-by #(or (nil? %) (empty? %) (= (subs % 0 1) "　"))) ; Partition by paragraph (empty line or indented line (common in BCCWJ)).
       (r/map (comp (partial into [])
                    (r/flatten)
                    (r/map split-japanese-sentence)
                    (r/filter identity)
                    (r/remove empty?)))
       (r/remove (partial every? empty?)) ; Remove paragraph boundaries.
       (r/foldcat)))

(defn add-tags [paragraphs]
  (->> paragraphs
       (r/map #(hash-map :tags #{}
                         :sentences %))
       (into [])))

(comment
  (bench (lines->paragraph-sentences ["フェイスブック（ＦＢ）やツイッターなどソーシャルメディアを使った採用活動が、多くの企業に広がっている。ＦＢでの会社説明会やＯＢ・ＯＧ訪問受け付け、ソーシャルスキルをはかって面接代わりにする動きも出てきた。" "企業側のソーシャル活用法も多様になっている。" nil "「実際、どれくらいの休みが取れるのでしょうか」「女性にとって働きやすい職場ですか」。"])))
