INSERT INTO exchange_buy_limit_usage (character_id, obj_id, bucket_start, filled)
VALUES (?, ?, ?, ?)
ON CONFLICT (character_id, obj_id, bucket_start)
DO UPDATE SET filled = exchange_buy_limit_usage.filled + EXCLUDED.filled
