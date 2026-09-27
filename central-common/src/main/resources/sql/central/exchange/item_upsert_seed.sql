-- Params: obj_id, base_price, buy_limit, high_alch, shop_value, overwrite, overwrite.
--
-- base_price and buy_limit are filled only while they are null, so a re-import after a database
-- reset restores everything but never clobbers a value staff set by hand; pass overwrite to
-- replace them too. high_alch_value and shop_sell_value are cache facts, so a known value always
-- wins and a zero means "no data, leave what is there".
INSERT INTO exchange_items (obj_id, base_price, buy_limit, high_alch_value, shop_sell_value)
VALUES (?, ?, ?, COALESCE(?, 0), COALESCE(?, 0))
ON CONFLICT (obj_id) DO UPDATE SET
    base_price = CASE WHEN ? OR exchange_items.base_price IS NULL
                      THEN COALESCE(EXCLUDED.base_price, exchange_items.base_price)
                      ELSE exchange_items.base_price END,
    buy_limit = CASE WHEN ? OR exchange_items.buy_limit IS NULL
                     THEN COALESCE(EXCLUDED.buy_limit, exchange_items.buy_limit)
                     ELSE exchange_items.buy_limit END,
    high_alch_value = CASE WHEN EXCLUDED.high_alch_value > 0 THEN EXCLUDED.high_alch_value ELSE exchange_items.high_alch_value END,
    shop_sell_value = CASE WHEN EXCLUDED.shop_sell_value > 0 THEN EXCLUDED.shop_sell_value ELSE exchange_items.shop_sell_value END
RETURNING (xmax = 0) AS inserted, base_price, buy_limit
