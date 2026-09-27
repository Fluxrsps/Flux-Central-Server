UPDATE exchange_notifications
SET delivered_at = ?, delivered_world = ?
WHERE id = ANY(?) AND delivered_at IS NULL
