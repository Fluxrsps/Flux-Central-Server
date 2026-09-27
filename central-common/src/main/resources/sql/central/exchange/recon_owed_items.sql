-- Per (character, item): what the orders say they are owed against what the box actually holds.
-- A claim in flight has been taken off both sides, so no adjustment for exchange_claims here.
WITH owed AS (
    SELECT character_id, obj_id, sum(owed_items) AS v
    FROM exchange_orders WHERE owed_items > 0 GROUP BY 1, 2
),
held AS (
    SELECT character_id, obj_id, count AS v FROM exchange_collection_items
)
SELECT COALESCE(o.obj_id, h.obj_id), COALESCE(o.character_id, h.character_id), COALESCE(h.v, 0), COALESCE(o.v, 0)
FROM owed o
FULL OUTER JOIN held h USING (character_id, obj_id)
WHERE COALESCE(o.v, 0) <> COALESCE(h.v, 0)
