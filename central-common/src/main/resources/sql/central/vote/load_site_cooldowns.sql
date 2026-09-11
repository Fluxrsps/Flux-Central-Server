-- How long each site is still shut for this character. Rows that have already reopened are
-- dropped here rather than filtered by the caller, so an expired cooldown reads as "can vote".
SELECT
    site,
    CEIL(EXTRACT(EPOCH FROM (next_vote_at - CURRENT_TIMESTAMP)))::int AS seconds_remaining
FROM vote_site_cooldowns
WHERE character_id = ?
  AND next_vote_at > CURRENT_TIMESTAMP
