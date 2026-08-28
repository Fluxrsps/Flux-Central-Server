-- The player's own saved layouts.
--
-- The scratch slot is excluded by the caller passing its number: it is not a layout the player made
-- and must never appear in a list they pick from. See LayoutLimits.SCRATCH_SLOT.
SELECT slot, name_key, screen_width, screen_height
FROM character_layout_presets
WHERE character_id = ? AND slot <> ?
ORDER BY slot
