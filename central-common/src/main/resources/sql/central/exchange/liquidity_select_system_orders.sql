SELECT id, client_request_id
FROM exchange_orders
WHERE obj_id = ? AND source = 'SYSTEM' AND status IN ('OPEN', 'PARTIALLY_FILLED')
