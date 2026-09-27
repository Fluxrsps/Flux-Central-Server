-- Params: obj_id, base_price, buy_limit, overwrite (bool), overwrite. Fills what is null; with
-- overwrite, replaces what is set too.
INSERT INTO exchange_items (obj_id, base_price, buy_limit)
VALUES (?, ?, ?)
ON CONFLICT (obj_id) DO UPDATE SET
    base_price = CASE WHEN ? OR exchange_items.base_price IS NULL THEN COALESCE(EXCLUDED.base_price, exchange_items.base_price) ELSE exchange_items.base_price END,
    buy_limit = CASE WHEN ? OR exchange_items.buy_limit IS NULL THEN COALESCE(EXCLUDED.buy_limit, exchange_items.buy_limit) ELSE exchange_items.buy_limit END
RETURNING (xmax = 0) AS inserted, base_price, buy_limit
