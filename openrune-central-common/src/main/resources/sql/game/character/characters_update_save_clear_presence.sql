UPDATE account_characters
SET x = ?, z = ?, level = ?, last_login = ?, run_energy = ?,
    xp_rate_in_hundreds = ?, display_name = ?,
    members = ?, online_central_world_id = NULL, online_session_heartbeat = NULL,
    last_logout = CURRENT_TIMESTAMP
WHERE id = ?
