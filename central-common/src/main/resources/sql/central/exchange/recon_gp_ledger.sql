-- Per item: every GP ever reserved for a BUY is still reserved, was released back, was paid to
-- a seller, or was sinked as tax. Nothing else is allowed to happen to it.
WITH reserved AS (
    SELECT obj_id, sum(amount) AS v FROM exchange_events WHERE event_type = 'BUY_FUNDS_RESERVED' GROUP BY 1
),
open_now AS (
    SELECT obj_id, sum(reserved_amount) AS v FROM exchange_orders
    WHERE side = 'BUY' AND status IN ('OPEN', 'PARTIALLY_FILLED') GROUP BY 1
),
released AS (
    SELECT obj_id, sum(amount) AS v FROM exchange_events
    WHERE event_type = 'RESERVATION_RELEASED' AND amount IS NOT NULL GROUP BY 1
),
credited AS (
    SELECT obj_id, sum(amount) AS v FROM exchange_events WHERE event_type = 'GP_CREDITED' GROUP BY 1
),
sinked AS (
    SELECT obj_id, sum(amount) AS v FROM exchange_events WHERE event_type = 'TAX_SINKED' GROUP BY 1
)
SELECT r.obj_id, r.v,
       COALESCE(o.v, 0) + COALESCE(rel.v, 0) + COALESCE(c.v, 0) + COALESCE(s.v, 0)
FROM reserved r
LEFT JOIN open_now o USING (obj_id)
LEFT JOIN released rel USING (obj_id)
LEFT JOIN credited c USING (obj_id)
LEFT JOIN sinked s USING (obj_id)
WHERE r.v <> COALESCE(o.v, 0) + COALESCE(rel.v, 0) + COALESCE(c.v, 0) + COALESCE(s.v, 0)
