SELECT COALESCE(sum(filled), 0), COALESCE(sum(gp), 0)
FROM exchange_system_usage
WHERE obj_id = ? AND side = ? AND character_id = ? AND bucket_start > ?
