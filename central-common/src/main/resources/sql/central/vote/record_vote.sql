-- One more claimed vote for this character, in both the all-time and current-month tallies.
--
-- Upsert on both: a row per vote would grow without bound to answer a question that is only ever a
-- sum, and the monthly board is partitioned by period so an old month is never rewritten.
INSERT INTO vote_totals (character_id, display_name, total_votes, last_vote_at, updated_at)
VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (character_id) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    total_votes = vote_totals.total_votes + EXCLUDED.total_votes,
    last_vote_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
