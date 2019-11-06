(ns natsume-server.nlp.importers.livedoor
  (:require [clojure.spec.alpha :as s]
            [clojure.java.io :as io]
            [natsume-server.models.corpus :as corpus]
            [natsume-server.models.corpus-specs :refer :all]
            [natsume-server.nlp.text :as text]
            [natsume-server.utils.fs :as fs]))

(def genre-map
  {"dokujo-tsushin" "独女向け"
   "it-life-hack"   "IT"
   "kaden-channel"  "家電"
   "livedoor-homme" "男性向け"
   "movie-enter"    "映画"
   "peachy"         "女性向け"
   "smax"           "スマートフォン"
   "sports-watch"   "スポーツ"
   "topic-news"     "総合"})

(s/fdef parse-doc
  :args (s/cat :filename :corpus/file)
  :ret :corpus/document)

(defn parse-doc [filename]
  (let [[_url date title & body] (line-seq (io/reader filename))
        parent (fs/parent-name filename)
        author? (if-let [author (re-seq #"（([^）]+)）$" (last body))] (second (first author)) "")]
    #:document{:paragraphs (text/add-tags (text/lines->paragraph-sentences body))
               :metadata   #:metadata{:author     author?   ;; Other author formats are more common...
                                      :basename   (fs/base-name filename)
                                      :genre      ["livedoor" "ニュース" (genre-map parent)]
                                      :category   ["ニュース" (genre-map parent)]
                                      :year       (Integer/parseInt (first (re-seq #"^\d\d\d\d" date)))
                                      :title      title
                                      :permission true}}))

(defmethod corpus/metadata :corpus/livedoor [_] nil)

(defmethod corpus/files :corpus/livedoor
  [{:keys [corpus-dir]}]
  (into #{}
        (filter (fn [filename]
                  (not (#{"LICENSE" "README" "CHANGES"} (fs/base-name filename)))))
        (fs/walk-path corpus-dir "txt")))

(defmethod corpus/documents :corpus/livedoor
  [{:keys [files]}]
  (map parse-doc files))

(comment
  (s/explain :corpus/documents (corpus/documents {:corpus/type :corpus/livedoor :files (corpus/files {:corpus/type :corpus/livedoor :corpus-dir "/home/bor/Corpora/ldcc"})})))