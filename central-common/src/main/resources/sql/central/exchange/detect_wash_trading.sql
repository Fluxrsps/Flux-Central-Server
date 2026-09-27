-- The same item going A->B and B->A between one pair inside the window: volume with no purpose
-- but the chart.
WITH pairs AS (
    SELECT least(buyer_character_id, seller_character_id) AS a,
           greatest(buyer_character_id, seller_character_id) AS c,
           obj_id,
           count(*) AS n,
           count(DISTINCT buyer_character_id) AS directions
    FROM exchange_trades
    WHERE executed_at > ? AND source = 'PLAYER'
    GROUP BY 1, 2, 3
)
SELECT p.a, p.c, p.obj_id, p.n
FROM pairs p
WHERE p.directions = 2
  AND p.n >= ?
  AND NOT EXISTS (
      SELECT 1 FROM exchange_flags f
      WHERE f.kind = 'WASH_TRADING' AND f.status = 'OPEN'
        AND f.character_id = p.a AND f.counterparty_id = p.c AND f.obj_id = p.obj_id
  )
