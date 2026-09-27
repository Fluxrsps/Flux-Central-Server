SELECT {columns}
FROM exchange_orders
WHERE character_id = ?
  AND status IN ('PENDING', 'OPEN', 'PARTIALLY_FILLED')
ORDER BY created_at, id
