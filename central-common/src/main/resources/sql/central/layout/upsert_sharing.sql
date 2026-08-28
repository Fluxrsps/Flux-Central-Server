INSERT INTO character_layout_settings (character_id, shared, updated_at)
VALUES (?, ?, CURRENT_TIMESTAMP)
ON CONFLICT (character_id) DO UPDATE SET
    shared = EXCLUDED.shared,
    updated_at = CURRENT_TIMESTAMP
