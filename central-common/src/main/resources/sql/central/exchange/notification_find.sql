SELECT id, character_id, kind, order_id, obj_id, payload::text, delivered_at
FROM exchange_notifications
WHERE id = ?
