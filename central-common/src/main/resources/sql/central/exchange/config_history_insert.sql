INSERT INTO exchange_config_history (key, old_value, new_value, changed_by, reason, changed_at)
VALUES (?, ?::jsonb, ?::jsonb, ?, ?, ?)
