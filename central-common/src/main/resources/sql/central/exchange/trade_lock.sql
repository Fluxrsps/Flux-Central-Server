SELECT id, buy_order_id, sell_order_id, buyer_character_id, seller_character_id, obj_id, quantity,
       unit_price, gross_value, tax, net_value, source, executed_at, reversed_at
FROM exchange_trades
WHERE id = ?
FOR UPDATE
