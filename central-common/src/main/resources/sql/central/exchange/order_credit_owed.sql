-- Attribution only: the pool is credited alongside this, and the two must move together.
UPDATE exchange_orders
SET owed_items = owed_items + ?, owed_gp = owed_gp + ?, updated_at = CURRENT_TIMESTAMP
WHERE id = ?
