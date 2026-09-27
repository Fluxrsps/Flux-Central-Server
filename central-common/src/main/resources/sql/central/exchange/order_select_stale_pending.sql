-- PENDING orders old enough that no second step can still be in flight for them.
SELECT id
FROM exchange_orders
WHERE character_id = ?
  AND status = 'PENDING'
  AND created_at <= ?
ORDER BY id
