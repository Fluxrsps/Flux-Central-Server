-- Per item: every unit ever reserved for a SELL is still reserved, was released back, or was
-- credited to a buyer.
WITH reserved AS (
    SELECT obj_id, sum(amount) AS v FROM exchange_events WHERE event_type = 'SELL_ITEMS_RESERVED' GROUP BY 1
),
open_now AS (
    SELECT obj_id, sum(reserved_amount) AS v FROM exchange_orders
    WHERE side = 'SELL' AND status IN ('OPEN', 'PARTIALLY_FILLED') GROUP BY 1
),
released AS (
    SELECT obj_id, sum(quantity) AS v FROM exchange_events
    WHERE event_type = 'RESERVATION_RELEASED' AND quantity IS NOT NULL GROUP BY 1
),
credited AS (
    SELECT obj_id, sum(quantity) AS v FROM exchange_events WHERE event_type = 'ITEM_CREDITED' GROUP BY 1
)
SELECT r.obj_id, r.v, COALESCE(o.v, 0) + COALESCE(rel.v, 0) + COALESCE(c.v, 0)
FROM reserved r
LEFT JOIN open_now o USING (obj_id)
LEFT JOIN released rel USING (obj_id)
LEFT JOIN credited c USING (obj_id)
WHERE r.v <> COALESCE(o.v, 0) + COALESCE(rel.v, 0) + COALESCE(c.v, 0)
