SELECT display, x, y, width, height
FROM character_layout_displays
WHERE preset_id = ?
ORDER BY display
