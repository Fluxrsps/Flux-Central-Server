-- Which display mode people actually use.
--
-- Counted at login rather than at the moment of switching. A switch count answers "how often does
-- someone change their mind", which is a different and much noisier question - one player toggling
-- back and forth would outvote a hundred who picked a frame once and stayed there. What a player was
-- on when they logged in is what they chose to keep.
--
-- A character's *first* login is deliberately not counted: they did not pick that frame, it is
-- whatever `openLoginGameframe` fell back to. Counting it would make the default look popular on the
-- strength of being the default.
--
-- Per character rather than one global tally, so the web can ask both questions: sum `logins` for
-- raw usage, count rows for how many distinct people settled on each frame.
CREATE TABLE IF NOT EXISTS character_gameframe_usage (
    character_id INTEGER NOT NULL REFERENCES account_characters (id) ON DELETE CASCADE,
    -- The gameframe's top-level interface id, which is what `varbit.gameframe_toplevel` holds and
    -- what `gameframes` is keyed by. An id rather than a name because that is what the game server
    -- has in hand; the web resolves it against the cache the same way anything else does.
    gameframe INTEGER NOT NULL,
    logins INTEGER NOT NULL DEFAULT 0,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (character_id, gameframe)
);

-- The leaderboard read: totals per frame, answered from the index without touching the table.
--
--   SELECT gameframe, SUM(logins) AS logins, COUNT(*) AS characters
--   FROM character_gameframe_usage
--   GROUP BY gameframe
--   ORDER BY logins DESC;
CREATE INDEX IF NOT EXISTS idx_character_gameframe_usage_frame
    ON character_gameframe_usage (gameframe, logins);
