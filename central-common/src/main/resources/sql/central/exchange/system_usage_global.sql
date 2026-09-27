SELECT COALESCE(sum(filled), 0), COALESCE(sum(gp), 0)
FROM exchange_system_usage
WHERE side = ? AND character_id = 0 AND bucket_start > ?
