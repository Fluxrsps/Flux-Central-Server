-- Counts one import of somebody else's layout.
--
-- Keyed by owner and slot rather than by preset id, because that is what the caller has: a shared
-- fetch names a display name and a slot, and the id is an implementation detail it never sees.
UPDATE character_layout_presets
SET import_count = import_count + 1
WHERE character_id = ? AND slot = ?
