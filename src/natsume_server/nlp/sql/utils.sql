-- :name token-stream :? :*
-- :doc Get stream of tokens from all corpora
-- select orth from tokens order by sentences_id, position limit 10
select sources.basename, sources.genre, string_agg(:i:features, ' ' order by :i:table.sentences_id, :i:table.position) from :i:table, sentences, sources where :i:table.sentences_id=sentences.id and sentences.sources_id=sources.id and :i:table.pos ~ '(noun|verb|adverb|adjective|preposition)' group by sources_id, basename, genre order by sources_id

-- :name export-corpus :!
-- :doc Exports all corpus data to given temporary file path
-- FIXME: make real feature vec (with ||)
copy (select string_agg(:i:features, ' ') from :i:table group by sentences_id order by sentences_id) to :export-path
