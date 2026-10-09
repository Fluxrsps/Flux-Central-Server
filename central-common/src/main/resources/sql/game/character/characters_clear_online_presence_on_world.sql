UPDATE character_progress
SET online_central_world_id = NULL, online_session_heartbeat = NULL
WHERE online_central_world_id = ?
