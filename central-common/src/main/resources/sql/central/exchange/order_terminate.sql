UPDATE exchange_orders
SET status = ?, cancel_reason = ?, reserved_amount = 0, cancelled_at = CURRENT_TIMESTAMP
WHERE id = ?
