SELECT id
FROM exchange_orders
WHERE expires_at IS NOT NULL
  AND expires_at <= ?
  AND status IN ('OPEN', 'PARTIALLY_FILLED')
ORDER BY expires_at
LIMIT ?
