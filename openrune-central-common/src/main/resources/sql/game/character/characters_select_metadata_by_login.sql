SELECT
    a.id AS account_id,
    a.account_name,
    a.rights,
    a.email,
    a.twofa_enabled,
    a.twofa_secret,
    a.twofa_last_verified,
    a.known_device,
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
