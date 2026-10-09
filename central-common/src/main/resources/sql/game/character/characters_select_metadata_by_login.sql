/* Identity comes from `public`, the save from the world-type schema on the search path. LEFT JOIN
   because a character that has never played this world type has no progress row yet; the caller
   creates one and treats the login as fresh for that mode. */
SELECT
    a.id AS account_id,
    a.account_name,
    a.rights,
    a.discord_id,
    c.donator_rank,
    c.game_mode,
    a.email,
    a.discord_id,
    a.two_factor_secret,
    a.two_factor_recovery_codes,
    a.two_factor_confirmed_at,
    c.display_name,
    c.previous_display_name,
    c.display_name_changed_at,
    c.members,
    c.id AS character_id,
    c.created_at AS character_created_at,
    c.muted_until,
    c.banned_until,
    p.character_id IS NULL AS progress_missing,
    p.world_id,
    COALESCE(p.x, 3200) AS x,
    COALESCE(p.z, 3200) AS z,
    COALESCE(p.level, 0) AS level,
    p.last_login,
    p.last_logout,
    COALESCE(p.run_energy, 10000) AS run_energy,
    COALESCE(p.xp_rate_in_hundreds, 100) AS xp_rate_in_hundreds,
    p.online_central_world_id,
    p.online_session_heartbeat
FROM accounts a
JOIN account_characters c ON c.account_id = a.id
LEFT JOIN character_progress p ON p.character_id = c.id
WHERE LOWER(a.account_name) = LOWER(?)
ORDER BY c.id ASC
LIMIT 1
