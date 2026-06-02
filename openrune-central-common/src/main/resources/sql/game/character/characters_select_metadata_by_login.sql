SELECT
    a.id AS account_id,
    a.login_username,
    a.rights,
    c.display_name,
    c.email,
    c.members,
    c.twofa_enabled,
    c.twofa_secret,
    c.twofa_last_verified,
    c.known_device,
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
WHERE c.realm_id = ?
    AND LOWER(a.login_username) = ?
ORDER BY c.id ASC
LIMIT 1
