UPDATE exchange_orders
SET filled_quantity = ?,
    reserved_amount = ?,
    status = ?,
    completed_at = CASE WHEN ? = 'FILLED' THEN CURRENT_TIMESTAMP ELSE completed_at END
WHERE id = ?
