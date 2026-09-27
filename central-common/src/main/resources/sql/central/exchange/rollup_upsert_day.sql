-- Params: obj_id, day (x4 pairs), rolled_up_at. Distinct traders come from the per-hour trader rows,
-- because counting distinct characters cannot be done by summing hourly counts.
INSERT INTO exchange_stats_daily (
    obj_id, day, open_price, close_price, low_price, high_price, vwap_numerator, vwap_denominator,
    volume, transaction_count, distinct_buyers, distinct_sellers, player_volume, system_volume,
    seeded_volume, tax_collected, rolled_up_at
)
SELECT
    h.obj_id,
    (h.bucket_start AT TIME ZONE 'UTC')::date,
    (SELECT o.open_price FROM exchange_stats_hourly o
      WHERE o.obj_id = ? AND (o.bucket_start AT TIME ZONE 'UTC')::date = ? ORDER BY o.bucket_start LIMIT 1),
    (SELECT c.close_price FROM exchange_stats_hourly c
      WHERE c.obj_id = ? AND (c.bucket_start AT TIME ZONE 'UTC')::date = ? ORDER BY c.bucket_start DESC LIMIT 1),
    min(h.low_price), max(h.high_price), sum(h.vwap_numerator), sum(h.vwap_denominator),
    sum(h.volume), sum(h.transaction_count),
    (SELECT count(DISTINCT t.character_id) FROM exchange_stats_hourly_traders t
      WHERE t.obj_id = ? AND t.side = 'BUY' AND (t.bucket_start AT TIME ZONE 'UTC')::date = ?),
    (SELECT count(DISTINCT t.character_id) FROM exchange_stats_hourly_traders t
      WHERE t.obj_id = ? AND t.side = 'SELL' AND (t.bucket_start AT TIME ZONE 'UTC')::date = ?),
    sum(h.player_volume), sum(h.system_volume), sum(h.seeded_volume), sum(h.tax_collected), ?
FROM exchange_stats_hourly h
WHERE h.obj_id = ? AND (h.bucket_start AT TIME ZONE 'UTC')::date = ?
GROUP BY h.obj_id, (h.bucket_start AT TIME ZONE 'UTC')::date
ON CONFLICT (obj_id, day) DO UPDATE SET
    open_price = EXCLUDED.open_price, close_price = EXCLUDED.close_price,
    low_price = EXCLUDED.low_price, high_price = EXCLUDED.high_price,
    vwap_numerator = EXCLUDED.vwap_numerator, vwap_denominator = EXCLUDED.vwap_denominator,
    volume = EXCLUDED.volume, transaction_count = EXCLUDED.transaction_count,
    distinct_buyers = EXCLUDED.distinct_buyers, distinct_sellers = EXCLUDED.distinct_sellers,
    player_volume = EXCLUDED.player_volume, system_volume = EXCLUDED.system_volume,
    seeded_volume = EXCLUDED.seeded_volume, tax_collected = EXCLUDED.tax_collected,
    rolled_up_at = EXCLUDED.rolled_up_at
