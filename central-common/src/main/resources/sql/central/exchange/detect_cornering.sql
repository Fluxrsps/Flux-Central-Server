-- One account holding most of an item's open BUY depth (by GP) or of its recent buy volume.
WITH depth AS (
    SELECT obj_id, character_id, sum((quantity - filled_quantity) * limit_price) AS v
    FROM exchange_orders
    WHERE side = 'BUY' AND status IN ('OPEN', 'PARTIALLY_FILLED') AND source = 'PLAYER'
    GROUP BY 1, 2
),
depth_total AS (SELECT obj_id, sum(v) AS tv FROM depth GROUP BY 1),
bought AS (
    SELECT obj_id, buyer_character_id AS character_id, sum(gross_value) AS v
    FROM exchange_trades
    WHERE executed_at > ? AND source = 'PLAYER'
    GROUP BY 1, 2
),
bought_total AS (SELECT obj_id, sum(v) AS tv FROM bought GROUP BY 1),
candidates AS (
    SELECT d.obj_id, d.character_id, 'DEPTH' AS basis, d.v, t.tv
    FROM depth d JOIN depth_total t USING (obj_id)
    UNION ALL
    SELECT b.obj_id, b.character_id, 'VOLUME', b.v, t.tv
    FROM bought b JOIN bought_total t USING (obj_id)
)
SELECT obj_id, character_id, basis, v, tv
FROM candidates x
WHERE tv >= ?
  AND v * 10000 >= tv * ?
  AND NOT EXISTS (
      SELECT 1 FROM exchange_flags f
      WHERE f.kind = 'CORNERING' AND f.status = 'OPEN'
        AND f.character_id = x.character_id AND f.obj_id = x.obj_id
  )
