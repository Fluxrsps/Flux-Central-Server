SELECT id, kind, order_id, obj_id, payload::text
FROM exchange_notifications
WHERE character_id = ? AND delivered_at IS NULL
ORDER BY id
LIMIT ?
