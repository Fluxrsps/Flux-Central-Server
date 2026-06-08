UPDATE discord_link_pending
SET wrong_attempts = wrong_attempts + 1
WHERE account_id = ?
RETURNING wrong_attempts
