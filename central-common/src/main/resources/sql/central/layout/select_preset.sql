SELECT id, slot, name_key, screen_width, screen_height
FROM character_layout_presets
WHERE character_id = ? AND slot = ?
