-- Params: t-24h, t-48h, t-24h, t-24h, t-48h, t-24h. Whole-exchange volume and distinct traders,
-- last day versus the day before: a bot wave hitting many items shows here before any one item.
SELECT
    (SELECT COALESCE(sum(player_volume + system_volume), 0) FROM exchange_stats_hourly WHERE bucket_start > ?) AS volume_24h,
    (SELECT COALESCE(sum(player_volume + system_volume), 0) FROM exchange_stats_hourly WHERE bucket_start > ? AND bucket_start <= ?) AS volume_prev_24h,
    (SELECT count(DISTINCT character_id) FROM exchange_stats_hourly_traders WHERE bucket_start > ?) AS traders_24h,
    (SELECT count(DISTINCT character_id) FROM exchange_stats_hourly_traders WHERE bucket_start > ? AND bucket_start <= ?) AS traders_prev_24h
