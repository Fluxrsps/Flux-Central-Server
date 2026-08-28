-- One more login on this frame for this character.
--
-- Upsert rather than insert-then-update: the common case is a character who has logged in on this
-- frame before, and a row per login would make the table grow with playtime for an answer that is
-- only ever a total.
INSERT INTO character_gameframe_usage (character_id, gameframe, logins, last_seen_at)
VALUES (?, ?, 1, CURRENT_TIMESTAMP)
ON CONFLICT (character_id, gameframe) DO UPDATE SET
    logins = character_gameframe_usage.logins + 1,
    last_seen_at = CURRENT_TIMESTAMP
