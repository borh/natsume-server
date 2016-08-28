-- :name token-stream :? :*
-- :doc Get stream of tokens from all corpora
-- select orth from tokens order by sentences_id, position limit 10
select string_agg(orth, ' ') from tokens group by sentences_id order by sentences_id

-- :name export-corpus :!
-- :doc Exports all corpus data to given temporary file path
-- FIXME: make real feature vec (with ||)
copy (select string_agg(:i:features, ' ') from :i:table group by sentences_id order by sentences_id) to :export-path
