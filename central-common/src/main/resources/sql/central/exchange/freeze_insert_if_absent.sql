INSERT INTO exchange_freezes (scope, target_id, reason_code, reason, staff_character_id, created_at)
VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT (scope, target_id) WHERE lifted_at IS NULL DO NOTHING
