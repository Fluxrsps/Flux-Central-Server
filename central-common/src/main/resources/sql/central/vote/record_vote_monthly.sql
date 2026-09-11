-- The current-month half of record_vote.sql. Period is passed in as YYYY-MM so the caller's clock
-- decides the month boundary, not the database's.
INSERT INTO vote_monthly (character_id, period, display_name, votes, updated_at)
VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
ON CONFLICT (character_id, period) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    votes = vote_monthly.votes + EXCLUDED.votes,
    updated_at = CURRENT_TIMESTAMP
