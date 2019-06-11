(ns natsume-server.utils.export
  (:require [clojure.spec.alpha :as s]
            [dk.ative.docjure.spreadsheet :as spreadsheet]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [natsume-server.nlp.annotation-middleware :as am]
            [natsume-server.nlp.collocations :as collocations])
  (:import [java.security MessageDigest]))

(s/def :filename/string string?)

(s/fdef sha256
  :args :filename/string
  :ret string?)

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
                                     (string/join "," (map name v))
                                     v)))))
                     [(mapv name headers)]
                     data))
        sheet (spreadsheet/select-sheet title wb)
        header-row (first (spreadsheet/row-seq sheet))]
    (spreadsheet/set-row-style! header-row (spreadsheet/create-cell-style! wb {:font {:bold true}}))
    (spreadsheet/save-workbook! filename wb)))

(defn write-local!
  [{:keys [corpus basename paragraphs extraction-file extraction-unit extraction-features]}]
  (with-open [writer (io/writer extraction-file :append true)]
    (csv/write-csv writer
                   (into []
                         (mapcat
                           (fn [{:keys [tags sentences]}]
                             (let [tags-string (string/join "," (map name tags))]
                               (pmap
                                 (fn [sentence]
                                   (let [sentence (string/replace sentence #"[\n\t]" " ")
                                         extracted-text
                                         (case extraction-unit
                                           :text sentence
                                           :suw (string/join
                                                  " "
                                                  (sequence (comp (mapcat :chunk/tokens)
                                                                  (map extraction-features))
                                                            (am/sentence->tree sentence)))

                                           :unigrams (->> (am/sentence->tree sentence)
                                                          (collocations/extract-unigrams)
                                                          (map extraction-features)
                                                          (string/join " ")))]
                                     [corpus
                                      basename
                                      tags-string
                                      extracted-text]))
                                 sentences))))
                         paragraphs)
                   :separator \tab
                   :quote? false)))

(comment
  ;; FIXME convert
  (defn extract-corpus! [sampling corpus-dir extraction-unit extraction-features extraction-file]
    (let [extraction-features (if (= extraction-unit :suw)  ;; TODO refactor :string into :lemma for unigrams (need to change schema!)
                                extraction-features
                                (if (= extraction-features :morpheme/lemma)
                                  :string
                                  extraction-features))
          corpus-type (condp re-seq (.toString (fs/name corpus-dir))
                        #"(?i)wiki" :wikipedia
                        #"(?i)(newspaper|mai|yomi)" :newspaper
                        #"(?i)(LB|OB|OC|OL|OM|OP|OT|OV|OW|OY|PB|PM|PN)" :bccwj
                        :generic)

          file-computation
          (graph/eager-compile
            (-> (case corpus-type
                  :wikipedia (dissoc file-graph :paragraphs)
                  :newspaper (dissoc file-graph :paragraphs)
                  :bccwj (dissoc bccwj-file-graph :corpus)
                  :generic file-graph)
                (dissoc :sources-id)
                (assoc :persist
                       (fnk [corpus basename paragraphs]
                            (with-open [writer (io/writer extraction-file :append true)]
                              (csv/write-csv writer
                                             (into []
                                                   (mapcat
                                                     (fn [{:keys [tags sentences]}]
                                                       (let [tags-string (string/join "," (map name tags))]
                                                         (pmap
                                                           (fn [sentence]
                                                             (let [sentence (string/replace sentence #"[\n\t]" " ")
                                                                   extracted-text
                                                                   (case extraction-unit
                                                                     :text sentence
                                                                     :suw (string/join
                                                                            " "
                                                                            (sequence (comp (mapcat :chunk/tokens)
                                                                                            (map extraction-features))
                                                                                      (am/sentence->tree sentence)))

                                                                     :unigrams (->> (am/sentence->tree sentence)
                                                                                    (collocations/extract-unigrams)
                                                                                    (map extraction-features)
                                                                                    (string/join " ")))]
                                                               [corpus
                                                                basename
                                                                tags-string
                                                                extracted-text]))
                                                           sentences))))
                                                   paragraphs)
                                             :separator \tab
                                             :quote? false))))))

          corpus-computation
          (graph/eager-compile
            (assoc
              (case corpus-type
                :wikipedia wikipedia-graph
                :newspaper newspaper-graph
                :bccwj bccwj-graph
                :generic corpus-graph)
              :persist
              (case corpus-type
                :wikipedia
                (fnk [files]
                     (doseq [file files]
                       (let [{:keys [sources paragraphs]} file]
                         (file-computation {:corpus     (string/join "." (:metadata/genre sources))
                                            :basename   (:basename sources)
                                            :paragraphs paragraphs}))))

                :newspaper                                  ;; copy of :wikipedia
                (fnk [files]
                     (doseq [file files]
                       (let [{:keys [sources paragraphs]} file]
                         (file-computation {:corpus     (string/join "." (:metadata/genre sources))
                                            :basename   (:basename sources)
                                            :paragraphs paragraphs}))))

                :bccwj
                (fnk [sources files]
                     (let [file-to-genre (reduce
                                           (fn [m {:keys [basename metadata/genre]}]
                                             (assoc m basename (string/join "." genre)))
                                           {}
                                           sources)]
                       (doseq [file files]
                         (file-computation {:filename file :basename (base-name file) :corpus (file-to-genre (base-name file))}))))

                :generic
                (fnk [sources files file-bases]
                     ;; For non-BCCWJ and Wikipedia sources, we might want to run some sanity checks first.
                     (let [file-to-genre (reduce
                                           (fn [m {:keys [basename metadata/genre]}]
                                             (assoc m basename (string/join "." genre)))
                                           {}
                                           sources)

                           sources-basenames (set (map :basename sources))
                           basenames-missing-source (set/difference file-bases sources-basenames)
                           basenames-missing-from-fs (set/difference sources-basenames file-bases)]
                       (binding [*print-length* 10]
                         (when (seq basenames-missing-source)
                           (timbre/debugf "%d basenames missing from sources.tsv: (Warning: will be skipped!) %s"
                                          (count basenames-missing-source)
                                          basenames-missing-source))
                         (when (seq basenames-missing-from-fs)
                           (timbre/debugf "%d basenames in sources.tsv missing on filesystem: %s"
                                          (count basenames-missing-from-fs)
                                          basenames-missing-from-fs)))
                       (doseq [file (remove (fn [f] (contains? basenames-missing-source (base-name f))) files)]
                         (file-computation {:filename file :basename (base-name file) :corpus (file-to-genre (base-name file))})))))))]
      (corpus-computation {:corpus-dir corpus-dir :sampling-options (assoc sampling :ratio 0.0)}))))
