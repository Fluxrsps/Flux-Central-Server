INSERT INTO character_layout_windows
    (preset_id, panel, x, y, width, height, host_panel, tab_order)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (preset_id, panel) DO NOTHING
