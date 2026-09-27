SELECT side, limit_price, quantity - filled_quantity, created_at
FROM exchange_orders
WHERE obj_id = ?
  AND status IN ('OPEN', 'PARTIALLY_FILLED')
  AND limit_price BETWEEN ? AND ?
