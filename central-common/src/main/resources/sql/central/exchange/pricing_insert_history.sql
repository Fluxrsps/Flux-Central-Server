INSERT INTO exchange_market_price_history (
    obj_id, computed_at, old_price, new_price, vwap, pressure, confidence, anchor_weight, anchor_price,
    market_target, anchored_target, max_change, clamped, trades_used, trades_excluded,
    distinct_buyers, distinct_sellers, buy_depth, sell_depth, seeded_weight, reason, staff_character_id
)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
