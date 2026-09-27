INSERT INTO exchange_config (key, value, changed_by, reason, changed_at)
VALUES (?, ?::jsonb, ?, ?, ?)
ON CONFLICT (key) DO UPDATE SET
    value = EXCLUDED.value, changed_by = EXCLUDED.changed_by,
    reason = EXCLUDED.reason, changed_at = EXCLUDED.changed_at
