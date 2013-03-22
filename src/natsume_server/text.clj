;; # Text manipulation utilities
(ns natsume-server.text
  (:require [clojure.string :as string]
            [clojure.core.reducers :as r]
            [natsume-server.utils :as u]
            [taoensso.timbre :as log]
            [natsume-server.log-config :as lc]))

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
  (apply str (for [codepoint codepoints] (char codepoint))))

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
                        (if (and (and y z)
                                 (delimiter-set y)                   ; ...|x |y |z |...
                                 (not (or (and (alphanumerics-set x) ;|   |５|．|０|
                                               (not= \。 y)          ;|mpl|e |. |c |om/
                                               (alphanumerics-set z));|   |  |  |  |
                                          (closing-quotation-set z)  ; ...|る|。|）|と言った
                                          (delimiter-set z))))       ; ...|。|。|。|
                          (conj (pop (pop a)) z \newline y x)
                          (conj a x))))))
       reverse
       (apply str)
       string/split-lines))

(def paragraph-split-re
  (re-pattern "([\r\n]{4,}|[\r\n]{2,}　|\n{2,})"))

(def paragraph-split-non-capturing-re
  (re-pattern "(?<=[\r\n]{4,}|[\r\n]{2,}　|\n{2,})"))

;; This is the first function that deals with the file contents.

(defn string->sentences-annotation
  [s]
  (let [])
  (reduce
   (fn [accum char]
     (cond
      (re-seq opening-quotation char) (update-in accum [:quotation-balance] inc)
      (re-seq closing-quotation char) (update-in accum [:quotation-balance] dec)
      (and (re-seq delimiter char) (zero? (:quotation-balance accum))) (update-in accum [:parsed-text] conj (str (:sentence accum)))
       ))
   {:text s
    :sentence '()
    :parsed-text []
    :quotation-balance 0}
   (reverse (vec s))))

(defn re-pos
  [re s]
  (loop [m (re-matcher re s)
         res []]
    (if (.find m)
      (recur m (conj res [(.start m) (.group m)]))
      res)))

(defn text->sentences-annotation
  [s]
  (let [paragraph-breaks-no (re-pos paragraph-split-non-capturing-re s)
        paragraph-breaks (re-pos paragraph-split-re s)
        paragraphs-no    (string/split s paragraph-split-non-capturing-re)
        paragraphs       (string/split s paragraph-split-re)
        sentences        (map #(string/split % sentence-split-re) paragraphs)
        sentence-breaks  (map #(re-pos sentence-split-re %) paragraphs)]
    {:text             s
     :paragraph-breaks paragraph-breaks
     :paragraphs       paragraphs
     :sentence-breaks  sentence-breaks
     :sentences        sentences
     }))

;; TODO FIXME make a stand-off interface to the text that keeps the
;; original but always returns, on demand (memoized?), paragraphs,
;; sentences, etc. The interface is required to provide character
;; offsets for everything and convert between them.
;; This might also pave the way to quotation detection support, or
;; even integration with kytea+eda/juman+knp.


(defn string->paragraphs
  "Splits string into paragraphs.
   Paragraphs are defined as:
   1) one or more non-empty lines delimited by one empty line or BOF/EOF
   2) lines prefixed with fullwidth unicode space '　'"
  [s]
  (log/trace s)
  (string/split s paragraph-split-re)) ; FIXME

(defn paragraph->sentences-2
  "Splits one pre-formatted paragraph into multiple sentences.
  Pre-formatting means that sentence splitting has already occured."
  [s]
  (remove string/blank? (string/split-lines s)))

(defn split-sentence-foldable [s]
  (into [] (r/remove string/blank? (string/split s sentence-split-re))))

(defn paragraph->sentences-2 [s]
  (println s)
  (into [] (r/flatten (r/map split-sentence-foldable (string/split-lines s)))))

(defn paragraph->sentences
  "Splits one paragraph into multiple sentences.

   Since it is hard to use Clojure's regexp functions (string/split)
  because they consume the matched group, we opt to add newlines to
  the end of all delimiters.???"
  [s]
  (vec
   (flatten
    (reduce (fn [accum sentence]
              (conj accum
                    (remove string/blank?
                            (flatten (string/split sentence sentence-split-re)))))
            []
            (string/split-lines s)))))

(defn paragraphs->sentences
  [ps]
  (mapv paragraph->sentences ps))

(defn string->sentences
  [s]
  (->> s
       string->paragraphs
       paragraphs->sentences))

(defn lines->paragraph-sentences
  [lines]
  (->> lines
       (u/partition-by #(or (nil? %) (= (subs % 0 1) "　"))) ; partition by paragraph (empty line or indented line (common in BCCWJ))
       (r/map (comp (partial into [])
                    (r/flatten)
                    (r/map split-japanese-sentence)
                    (r/filter identity)))
       (r/remove empty?) ; remove paragraph boundaries
       (r/foldcat)))


(comment
  (bench (lines->paragraph-sentences ["フェイスブック（ＦＢ）やツイッターなどソーシャルメディアを使った採用活動が、多くの企業に広がっている。ＦＢでの会社説明会やＯＢ・ＯＧ訪問受け付け、ソーシャルスキルをはかって面接代わりにする動きも出てきた。" "企業側のソーシャル活用法も多様になっている。" nil "「実際、どれくらいの休みが取れるのでしょうか」「女性にとって働きやすい職場ですか」。"])))
