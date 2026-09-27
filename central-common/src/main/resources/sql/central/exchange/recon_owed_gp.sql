-- Per character: coins the orders say they are owed against the coins the box actually holds.
WITH owed AS (
    SELECT character_id, sum(owed_gp) AS v FROM exchange_orders WHERE owed_gp > 0 GROUP BY 1
),
held AS (
    SELECT character_id, amount AS v FROM exchange_collection_gp
)
SELECT COALESCE(o.character_id, h.character_id), COALESCE(h.v, 0), COALESCE(o.v, 0)
FROM owed o
FULL OUTER JOIN held h USING (character_id)
WHERE COALESCE(o.v, 0) <> COALESCE(h.v, 0)
