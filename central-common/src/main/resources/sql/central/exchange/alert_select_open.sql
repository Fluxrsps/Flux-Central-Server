SELECT id, kind, severity, obj_id, details::text, created_at, last_seen_at
FROM exchange_alerts
WHERE status = 'OPEN'
ORDER BY created_at DESC
