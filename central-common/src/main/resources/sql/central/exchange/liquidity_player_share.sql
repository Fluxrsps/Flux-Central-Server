SELECT COALESCE(sum(player_volume), 0), COALESCE(sum(system_volume), 0)
FROM exchange_stats_hourly
WHERE obj_id = ? AND bucket_start > ?
