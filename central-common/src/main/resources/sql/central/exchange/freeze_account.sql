SELECT 1
FROM exchange_freezes
WHERE lifted_at IS NULL AND scope = 'ACCOUNT' AND target_id = ?
LIMIT 1
