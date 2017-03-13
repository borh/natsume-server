-- :name fulltext-stream-ltxtquery :? :*
-- :doc Get stream of sentences matching regular expression and genre.
SELECT so.title, so.author, so.year, so.genre, se.text FROM sentences AS se, sources AS so WHERE se.sources_id=so.id AND se.text ~ :query::text AND so.genre @ :genre::ltxtquery ORDER BY so.genre, so.year, so.title, se.id

-- :name fulltext-stream :? :*
-- :doc Get stream of sentences matching regular expression and genre.
SELECT
  se.id,
  so.title,
  so.author,
  so.year,
  so.genre,
  se_before.text AS before_text,
  se.text AS key_text,
  se_after.text AS after_text
FROM
  sources AS so
  JOIN sentences AS se ON (
    se.sources_id=so.id
  )
  LEFT OUTER JOIN sentences AS se_before ON (
    se_before.sources_id=so.id AND
    se.paragraph_order_id=se_before.paragraph_order_id AND
    se_before.sentence_order_id=se.sentence_order_id-1
  )
  LEFT OUTER JOIN sentences AS se_after ON (
    se_after.sources_id=so.id AND
    se.paragraph_order_id=se_after.paragraph_order_id AND
    se_after.sentence_order_id=se.sentence_order_id+1
  )
WHERE
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

-- :name expand-document :? :1
-- :doc Get document text given sentence id.
WITH d AS (
  SELECT sources.id
  FROM sources, sentences
  WHERE sentences.id=:id AND sentences.sources_id=sources.id
)
SELECT
  string_agg(paragraphs.text, E'\n' ORDER BY paragraphs.paragraph_order_id ASC) AS text
FROM
  (SELECT
      p_se.paragraph_order_id,
      string_agg(p_se.text, '' ORDER BY p_se.sentence_order_id ASC) AS text
    FROM d, sentences AS p_se
    WHERE p_se.sources_id=d.id
    GROUP BY
      p_se.paragraph_order_id
    ORDER BY
      p_se.paragraph_order_id ASC
  ) AS paragraphs
