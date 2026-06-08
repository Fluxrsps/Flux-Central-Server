SELECT
    a.id AS account_id,
    a.account_name,
    a.rights,
    a.discord_id,
    c.donator_rank,
    c.game_mode,
    a.email,
    a.email_verified_at,
    a.two_factor_secret,
    a.two_factor_recovery_codes,
    a.two_factor_confirmed_at,
    c.display_name,
    c.members,
    c.id AS character_id,
    c.world_id,
    c.x,
    c.z,
    c.level,
    c.created_at AS character_created_at,
    c.last_login,
    c.last_logout,
    c.muted_until,
    c.banned_until,
    c.run_energy,
    c.xp_rate_in_hundreds,
    c.online_central_world_id,
    c.online_session_heartbeat
FROM accounts a
JOIN account_characters c ON c.account_id = a.id
WHERE LOWER(a.account_name) = ?
ORDER BY c.id ASC
LIMIT 1
