SELECT count(*)
FROM exchange_orders
WHERE character_id = ?
  AND status IN ('PENDING', 'OPEN', 'PARTIALLY_FILLED')
