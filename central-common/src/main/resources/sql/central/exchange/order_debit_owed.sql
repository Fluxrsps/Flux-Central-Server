-- Never below zero: a claim is checked against the order's own owed amounts before it is taken.
UPDATE exchange_orders
SET owed_items = owed_items - ?, owed_gp = owed_gp - ?, updated_at = CURRENT_TIMESTAMP
WHERE id = ? AND owed_items >= ? AND owed_gp >= ?
