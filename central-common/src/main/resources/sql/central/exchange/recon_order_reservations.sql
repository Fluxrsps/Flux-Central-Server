SELECT id, obj_id, reserved_amount,
       CASE side WHEN 'BUY' THEN (quantity - filled_quantity) * limit_price ELSE quantity - filled_quantity END
FROM exchange_orders
WHERE status IN ('OPEN', 'PARTIALLY_FILLED')
  AND reserved_amount <> CASE side WHEN 'BUY' THEN (quantity - filled_quantity) * limit_price ELSE quantity - filled_quantity END
