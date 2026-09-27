-- Params: t-24h, t-7d, t-24h, t-48h, t-24h, t-24h, t-48h, t-24h. Per priced item: where it was a day
-- and a week ago, and volume and distinct traders in the last day versus the day before.
SELECT p.obj_id, p.price,
       (SELECT h.new_price FROM exchange_market_price_history h
         WHERE h.obj_id = p.obj_id AND h.computed_at <= ? ORDER BY h.computed_at DESC LIMIT 1) AS price_24h,
       (SELECT h.new_price FROM exchange_market_price_history h
         WHERE h.obj_id = p.obj_id AND h.computed_at <= ? ORDER BY h.computed_at DESC LIMIT 1) AS price_7d,
       (SELECT COALESCE(sum(s.player_volume + s.system_volume), 0) FROM exchange_stats_hourly s
         WHERE s.obj_id = p.obj_id AND s.bucket_start > ?) AS volume_24h,
       (SELECT COALESCE(sum(s.player_volume + s.system_volume), 0) FROM exchange_stats_hourly s
         WHERE s.obj_id = p.obj_id AND s.bucket_start > ? AND s.bucket_start <= ?) AS volume_prev_24h,
       (SELECT count(DISTINCT t.character_id) FROM exchange_stats_hourly_traders t
         WHERE t.obj_id = p.obj_id AND t.bucket_start > ?) AS traders_24h,
       (SELECT count(DISTINCT t.character_id) FROM exchange_stats_hourly_traders t
         WHERE t.obj_id = p.obj_id AND t.bucket_start > ? AND t.bucket_start <= ?) AS traders_prev_24h
FROM exchange_market_prices p
