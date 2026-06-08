UPDATE accounts
SET
    known_device = ?,
    discord_id = ?,
    two_factor_secret = ?,
    two_factor_recovery_codes = ?,
    two_factor_confirmed_at = ?,
    updated_at = CURRENT_TIMESTAMP
WHERE id = ?
