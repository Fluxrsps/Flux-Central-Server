UPDATE exchange_alerts
SET status = 'RESOLVED', resolved_by = ?, resolved_at = ?
WHERE id = ? AND status = 'OPEN'
