INSERT INTO exchange_trades (
    buy_order_id, sell_order_id, buyer_character_id, seller_character_id, maker_side, obj_id,
    quantity, unit_price, gross_value, tax_rate_bps, tax, net_value, source, flags,
    buy_filled_before, correlation_id, world
)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
RETURNING id, executed_at
