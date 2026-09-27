SELECT 1
FROM exchange_flags
WHERE kind = ? AND character_id = ? AND created_at > ?
LIMIT 1
