-- Marks every outstanding vote as claimed and says which of them the claim actually pays for.
--
-- Everything pending is cleared, but only votes inside vote_settings.claim_window_days are
-- claimable: an older backlog is dropped rather than carried forever, so nothing keeps checking it.
-- The window is read here rather than passed in, so the caller cannot disagree with the website
-- about what is claimable.
UPDATE vote_callbacks
SET claimed_at = CURRENT_TIMESTAMP
WHERE character_id = ?
  AND completed_at IS NOT NULL
  AND claimed_at IS NULL
RETURNING
    completed_at,
    completed_at >= CURRENT_TIMESTAMP - make_interval(
        days => (SELECT claim_window_days FROM vote_settings)
    ) AS claimable
