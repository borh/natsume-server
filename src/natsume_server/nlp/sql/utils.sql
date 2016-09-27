-- :name tokens-stream :? :*
-- :doc Get stream of tokens from all corpora
SELECT sources.basename, sources.genre, string_agg(:i:features, ' ' ORDER BY tokens.sentences_id, tokens.position) AS text FROM tokens, sentences, sources WHERE tokens.sentences_id=sentences.id AND sentences.sources_id=sources.id AND tokens.pos ~ '(noun|verb|adverb|adjective|preposition)' AND tokens.pos_2 != '非自立可能' GROUP BY sources_id, basename, genre ORDER BY sources_id

-- :name unigrams-stream :? :*
-- :doc Get stream of unigrams from all corpora
SELECT sources.basename, sources.genre, string_agg(:i:features, ' ' ORDER BY unigrams.sentences_id, unigrams.position) AS text FROM unigrams, sentences, sources WHERE unigrams.sentences_id=sentences.id AND sentences.sources_id=sources.id AND unigrams.pos ~ '(noun|verb|adverb|adjective|preposition)' GROUP BY sources_id, basename, genre ORDER BY sources_id

-- TODO Allow filtering of vocab at this level (w/ tf-idf etc.?)
