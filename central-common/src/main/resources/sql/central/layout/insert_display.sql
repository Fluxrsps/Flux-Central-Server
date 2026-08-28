INSERT INTO character_layout_displays (preset_id, display, x, y, width, height)
VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT (preset_id, display) DO NOTHING
