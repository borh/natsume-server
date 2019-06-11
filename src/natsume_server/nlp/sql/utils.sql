-- :name tokens-stream :? :*
-- :doc Get stream of tokens from all corpora
/* :require [clojure.string :as string] */
SELECT
  sources.basename,
  sources.genre,
  string_agg(:i:features, ' ' ORDER BY tokens.sentences_id, tokens.position) AS text -- TODO expand features with '/' delimiter
FROM
  tokens, sentences, sources
WHERE
      tokens.sentences_id=sentences.id
  AND sentences.sources_id=sources.id
--~ (when (seq (:pos-filter params)) (format "  AND tokens.pos ~ '(%s)'" (string/join "|" (:pos-filter params))))
--~ (when (seq (:pos-2-filter params)) (format "  AND tokens.pos_2 !~ '(%s)'" (string/join "|" (:pos-2-filter params))))
--~ (when (seq (:genre params)) (format "  AND sources.genre ~ '%s'::lquery" (:genre params)))
GROUP BY sources_id, basename, genre
ORDER BY sources_id

-- :name unigrams-stream :? :*
-- :doc Get stream of unigrams from all corpora
/* :require [clojure.string :as string] */
SELECT
  sources.basename,
  sources.genre,
  string_agg(:i:features, ' ' ORDER BY unigrams.sentences_id, unigrams.position) AS text
FROM
  unigrams, sentences, sources
WHERE
  unigrams.sentences_id=sentences.id
  AND sentences.sources_id=sources.id
--~ (when (seq (:pos-filter params)) (format "  AND unigrams.pos ~ '(%s)'" (string/join "|" (:pos-filter params))))
--~ (when (seq (:genre params)) (format "  AND sources.genre ~ '%s'::lquery" (:genre params)))
GROUP BY sources_id, basename, genre
ORDER BY sources_id

-- :name fulltext-stream-old :? :*
-- :doc Get stream of sentences matching regular expression and genre.
SELECT se.sentence_order_id AS sentence_order_id, so.id AS sources_id, so.title, so.author, so.year, so.genre, se.text FROM sentences AS se, sources AS so WHERE se.sources_id=so.id AND se.text ~ :query::text AND so.genre ~ :genre::lquery ORDER BY so.genre, so.year, so.title, se.sentence_order_id ASC
