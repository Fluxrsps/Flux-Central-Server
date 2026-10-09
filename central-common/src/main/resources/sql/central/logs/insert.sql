INSERT INTO activity_logs (log_type, occurred_at, account_id, character_id, world_id, world_type, payload)
VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
RETURNING id, log_uuid
