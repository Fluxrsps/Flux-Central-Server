-- Runs from Central, which has no world-type context, so it reads the cross-mode progress view.
SELECT online_central_world_id
FROM progress_all
WHERE character_id = ?
  AND online_session_heartbeat IS NOT NULL
  AND online_session_heartbeat >= ?
ORDER BY online_session_heartbeat DESC
LIMIT 1
