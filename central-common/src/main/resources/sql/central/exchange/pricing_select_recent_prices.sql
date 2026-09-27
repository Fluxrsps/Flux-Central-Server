SELECT new_price
FROM exchange_market_price_history
WHERE obj_id = ? AND computed_at > ?
ORDER BY new_price
