SELECT l.obj_id, p.stable_price, i.high_alch_value, i.shop_sell_value, i.frozen
FROM exchange_system_liquidity l
JOIN exchange_items i ON i.obj_id = l.obj_id
LEFT JOIN exchange_market_prices p ON p.obj_id = l.obj_id
WHERE l.enabled
ORDER BY l.obj_id
