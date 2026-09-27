INSERT INTO exchange_stats_hourly_traders (obj_id, bucket_start, character_id, side, volume)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT (obj_id, bucket_start, character_id, side)
DO UPDATE SET volume = exchange_stats_hourly_traders.volume + EXCLUDED.volume
