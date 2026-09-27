INSERT INTO exchange_system_usage (obj_id, side, bucket_start, character_id, filled, gp)
VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT (obj_id, side, bucket_start, character_id)
DO UPDATE SET filled = exchange_system_usage.filled + EXCLUDED.filled, gp = exchange_system_usage.gp + EXCLUDED.gp
