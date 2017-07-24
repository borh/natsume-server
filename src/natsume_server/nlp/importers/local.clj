(ns natsume-server.nlp.importers.local
  (:require [natsume-server.component.database :refer [connection]]
            [hugsql.core :as hugsql]))

;; TODO make a new data importer that makes dependency graph based data. That way we could use the model as a collocation suggestion tool, also for Natsume. (http://www.aclweb.org/anthology/P14-2050.pdf)

(hugsql/def-db-fns "natsume_server/nlp/sql/utils.sql")

(hugsql/def-sqlvec-fns "natsume_server/nlp/sql/utils.sql")

(defn stream-corpus
  "Streams tokens from database. Returns a sequence of maps with keys :basename, :genre, and :text. :text contains a string of whitespace-delimited tokens representing a whole document."
  ([unit-type features]
   (stream-corpus unit-type features nil nil))
  ([unit-type features pos-filter pos-2-filter]
   (case unit-type
     :suw (tokens-stream connection (cond-> {:features (first (map name features))}
                                      pos-filter (assoc :pos-filter pos-filter)
                                      pos-2-filter (assoc :pos-2-filter pos-2-filter)))
     :unigrams (unigrams-stream connection (cond-> {:features (first (map name features))}
                                             pos-filter (assoc :pos-filter pos-filter))))))

(defn extract-tokens
  [stream]
  (map :text stream))
