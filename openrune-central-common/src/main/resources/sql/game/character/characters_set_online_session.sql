UPDATE account_characters
SET online_central_world_id = ?, online_session_heartbeat = CURRENT_TIMESTAMP
WHERE id = ?
