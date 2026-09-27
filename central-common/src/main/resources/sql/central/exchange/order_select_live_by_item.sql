SELECT id
FROM exchange_orders
WHERE obj_id = ? AND status IN ('OPEN', 'PARTIALLY_FILLED')
ORDER BY id
