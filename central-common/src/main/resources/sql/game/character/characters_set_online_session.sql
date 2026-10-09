UPDATE character_progress
SET online_central_world_id = ?, online_session_heartbeat = CURRENT_TIMESTAMP
WHERE character_id = ?
