-- Oldest live BUY per item whose limit reaches the best SELL from a different character. These
-- are books the arrival-time pass missed (two orders arriving together, or a buy limit that has
-- since freed up); the sweeper runs a match pass on each.
SELECT DISTINCT ON (b.obj_id) b.id
FROM exchange_orders b
WHERE b.side = 'BUY'
  AND b.status IN ('OPEN', 'PARTIALLY_FILLED')
  AND EXISTS (
      SELECT 1
      FROM exchange_orders s
      WHERE s.obj_id = b.obj_id
        AND s.side = 'SELL'
        AND s.status IN ('OPEN', 'PARTIALLY_FILLED')
        AND s.character_id <> b.character_id
        AND s.limit_price <= b.limit_price
  )
ORDER BY b.obj_id, b.created_at, b.id
LIMIT ?
