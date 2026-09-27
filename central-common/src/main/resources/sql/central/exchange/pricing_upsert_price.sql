INSERT INTO exchange_market_prices (obj_id, price, stable_price, confidence, updated_at)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT (obj_id)
DO UPDATE SET price = EXCLUDED.price, stable_price = EXCLUDED.stable_price,
              confidence = EXCLUDED.confidence, updated_at = EXCLUDED.updated_at
