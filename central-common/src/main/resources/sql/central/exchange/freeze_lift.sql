UPDATE exchange_freezes
SET lifted_at = ?, lifted_by = ?
WHERE scope = ? AND target_id = ? AND lifted_at IS NULL
