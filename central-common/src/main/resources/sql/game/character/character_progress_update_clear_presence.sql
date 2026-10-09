UPDATE character_progress
SET x = ?, z = ?, level = ?, last_login = ?, run_energy = ?,
    xp_rate_in_hundreds = ?,
    online_central_world_id = NULL, online_session_heartbeat = NULL,
    last_logout = CURRENT_TIMESTAMP
WHERE character_id = ?
