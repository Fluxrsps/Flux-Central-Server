WITH credited AS (
    SELECT obj_id, sum(quantity) AS v FROM exchange_events
    WHERE event_type IN ('ITEM_CREDITED', 'RESERVATION_RELEASED') AND quantity IS NOT NULL GROUP BY 1
),
held AS (
    SELECT obj_id, sum(count) AS v FROM (
        SELECT obj_id, count FROM exchange_collection_items
        UNION ALL
        SELECT obj_id, count FROM exchange_claims WHERE status IN ('PENDING', 'COMPLETE') AND obj_id IS NOT NULL
    ) x GROUP BY 1
)
SELECT c.obj_id, c.v, COALESCE(h.v, 0)
FROM credited c
LEFT JOIN held h USING (obj_id)
WHERE c.v <> COALESCE(h.v, 0)
UNION ALL
SELECT h.obj_id, 0, h.v
FROM held h
LEFT JOIN credited c USING (obj_id)
WHERE c.obj_id IS NULL
