-- Records which streak milestones a claim paid out, and reports the streak it was paid against.
UPDATE vote_streaks
SET claimed_rewards = ?,
    updated_at = CURRENT_TIMESTAMP
WHERE character_id = ?
RETURNING streak_days, best_streak
