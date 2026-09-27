SELECT price, stable_price, confidence, updated_at
FROM exchange_market_prices
WHERE obj_id = ?
