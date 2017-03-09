-- :name fulltext-stream-ltxtquery :? :*
-- :doc Get stream of sentences matching regular expression and genre.
SELECT so.title, so.author, so.year, so.genre, se.text FROM sentences AS se, sources AS so WHERE se.sources_id=so.id AND se.text ~ :query::text AND so.genre @ :genre::ltxtquery ORDER BY so.genre, so.year, so.title, se.id

-- :name fulltext-stream :? :*
-- :doc Get stream of sentences matching regular expression and genre.
SELECT
se.sentence_order_id AS sentence_order_id,
se.paragraph_order_id AS paragraph_order_id,
so.id AS sources_id,
so.title,
so.author,
so.year,
so.genre,
se_before.text || se.text || se_after.text AS text
FROM
sentences AS se,
sentences AS se_before,
sentences AS se_after,
sources AS so
WHERE
se.sources_id=so.id AND
se_before.sources_id=so.id AND
se_after.sources_id=so.id AND
se.paragraph_order_id=se_before.paragraph_order_id AND
se.paragraph_order_id=se_after.paragraph_order_id AND
se_before.sentence_order_id=se.sentence_order_id-1 AND
se_after.sentence_order_id=se.sentence_order_id+1 AND
se.text ~ :query::text AND
so.genre ~ :genre::lquery
ORDER BY
so.genre,
so.year,
so.title,
se.sentence_order_id ASC

-- :name fulltext-stream-old :? :*
-- :doc Get stream of sentences matching regular expression and genre.
SELECT se.sentence_order_id AS sentence_order_id, so.id AS sources_id, so.title, so.author, so.year, so.genre, se.text FROM sentences AS se, sources AS so WHERE se.sources_id=so.id AND se.text ~ :query::text AND so.genre ~ :genre::lquery ORDER BY so.genre, so.year, so.title, se.sentence_order_id ASC
