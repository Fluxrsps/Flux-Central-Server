-- Params: day, day_start (x4), day, created_at.
INSERT INTO exchange_economy_snapshots (
    day, gp_in_buy_reservations, items_in_sell_reservations, gp_in_collection, items_in_collection,
    tax_sunk_total, tax_sunk_day, system_gp_paid_day, system_gp_taken_day, active_traders_day,
    active_orders, price_index, details, created_at
)
SELECT
    ?,
    (SELECT COALESCE(sum(reserved_amount), 0) FROM exchange_orders WHERE side = 'BUY' AND status IN ('OPEN', 'PARTIALLY_FILLED')),
    (SELECT COALESCE(sum(reserved_amount), 0) FROM exchange_orders WHERE side = 'SELL' AND status IN ('OPEN', 'PARTIALLY_FILLED')),
    (SELECT COALESCE(sum(amount), 0) FROM exchange_collection_gp),
    (SELECT COALESCE(sum(count), 0) FROM exchange_collection_items),
    (SELECT COALESCE(sum(tax), 0) FROM exchange_trades),
    (SELECT COALESCE(sum(tax), 0) FROM exchange_trades WHERE executed_at >= ?),
    (SELECT COALESCE(sum(gp), 0) FROM exchange_system_usage WHERE side = 'BUY' AND character_id = 0 AND bucket_start >= ?),
    (SELECT COALESCE(sum(gp), 0) FROM exchange_system_usage WHERE side = 'SELL' AND character_id = 0 AND bucket_start >= ?),
    (SELECT count(DISTINCT character_id) FROM exchange_stats_hourly_traders WHERE bucket_start >= ?),
    (SELECT count(*) FROM exchange_orders WHERE status IN ('OPEN', 'PARTIALLY_FILLED')),
    (SELECT CASE WHEN sum(d.volume) > 0
                 THEN sum(p.price::numeric * d.volume) / sum(d.volume) END
       FROM exchange_stats_daily d JOIN exchange_market_prices p ON p.obj_id = d.obj_id
      WHERE d.day >= ?),
    '{}'::jsonb,
    ?
ON CONFLICT (day) DO UPDATE SET
    gp_in_buy_reservations = EXCLUDED.gp_in_buy_reservations,
    items_in_sell_reservations = EXCLUDED.items_in_sell_reservations,
    gp_in_collection = EXCLUDED.gp_in_collection,
    items_in_collection = EXCLUDED.items_in_collection,
    tax_sunk_total = EXCLUDED.tax_sunk_total,
    tax_sunk_day = EXCLUDED.tax_sunk_day,
    system_gp_paid_day = EXCLUDED.system_gp_paid_day,
    system_gp_taken_day = EXCLUDED.system_gp_taken_day,
    active_traders_day = EXCLUDED.active_traders_day,
    active_orders = EXCLUDED.active_orders,
    price_index = EXCLUDED.price_index,
    created_at = EXCLUDED.created_at
