SELECT i.obj_id, i.base_price, i.launch_state, p.price
FROM exchange_items i
LEFT JOIN exchange_market_prices p ON p.obj_id = i.obj_id
WHERE NOT i.frozen
ORDER BY i.obj_id
