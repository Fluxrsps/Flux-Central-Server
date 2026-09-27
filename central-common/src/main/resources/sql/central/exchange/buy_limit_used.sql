SELECT COALESCE(sum(filled), 0)
FROM exchange_buy_limit_usage
WHERE character_id = ? AND obj_id = ? AND bucket_start > ?
