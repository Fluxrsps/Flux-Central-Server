-- Everything the game needs to render a character's voting state, in one round trip.
--
-- Unclaimed counts are derived from vote_callbacks rather than stored, so they cannot disagree
-- with claim_rewards.sql: a vote counts once it has completed, has not been claimed, and is still
-- inside vote_settings.claim_window_days.
SELECT
    COALESCE(s.streak_days, 0) AS streak_days,
    COALESCE(s.best_streak, 0) AS best_streak,
    COALESCE(s.last_vote_day, 0) AS last_vote_day,
    COALESCE(s.claimed_rewards, 0) AS claimed_rewards,
    COALESCE(t.total_votes, 0) AS total_votes,
    COALESCE(p.votes, 0) AS unclaimed_votes,
    COALESCE(p.days, 0) AS unclaimed_days
FROM (SELECT ? AS character_id) k
LEFT JOIN vote_streaks s ON s.character_id = k.character_id
LEFT JOIN vote_totals t ON t.character_id = k.character_id
LEFT JOIN (
    SELECT
        character_id,
        COUNT(*) AS votes,
        COUNT(DISTINCT DATE(completed_at)) AS days
    FROM vote_callbacks
    WHERE character_id = ?
      AND completed_at IS NOT NULL
      AND claimed_at IS NULL
      AND completed_at >= CURRENT_TIMESTAMP - make_interval(
          days => (SELECT claim_window_days FROM vote_settings)
      )
    GROUP BY character_id
) p ON p.character_id = k.character_id
