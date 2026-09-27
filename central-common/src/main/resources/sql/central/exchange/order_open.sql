UPDATE exchange_orders
SET status = 'OPEN', reserved_amount = ?, opened_at = CURRENT_TIMESTAMP
WHERE id = ? AND status = 'PENDING'
