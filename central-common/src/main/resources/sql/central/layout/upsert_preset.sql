-- RETURNING on the conflict branch as well as the insert one, so an overwrite hands back the id of
-- the row that was already there. ON CONFLICT DO NOTHING would return nothing on every save but the
-- first, and the windows would have no parent to hang from.
INSERT INTO character_layout_presets
    (character_id, slot, name_key, screen_width, screen_height, updated_at)
VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
ON CONFLICT (character_id, slot) DO UPDATE SET
    name_key = EXCLUDED.name_key,
    screen_width = EXCLUDED.screen_width,
    screen_height = EXCLUDED.screen_height,
    updated_at = CURRENT_TIMESTAMP
RETURNING id
