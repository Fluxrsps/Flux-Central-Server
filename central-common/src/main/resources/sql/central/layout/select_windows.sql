SELECT panel, x, y, width, height, host_panel, tab_order
FROM character_layout_windows
WHERE preset_id = ?
ORDER BY tab_order, panel
