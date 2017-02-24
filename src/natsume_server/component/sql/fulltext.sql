-- :name fulltext-stream-ltxtquery :? :*
-- :doc Get stream of sentences matching regular expression and genre.
SELECT so.title, so.author, so.year, so.genre, se.text FROM sentences AS se, sources AS so WHERE se.sources_id=so.id AND se.text ~ :query::text AND so.genre @ :genre::ltxtquery ORDER BY so.genre, so.year, so.title, se.id

-- :name fulltext-stream :? :*
-- :doc Get stream of sentences matching regular expression and genre.
SELECT so.title, so.author, so.year, so.genre, se.text FROM sentences AS se, sources AS so WHERE se.sources_id=so.id AND se.text ~ :query::text AND so.genre ~ :genre::lquery ORDER BY so.genre, so.year, so.title, se.id
