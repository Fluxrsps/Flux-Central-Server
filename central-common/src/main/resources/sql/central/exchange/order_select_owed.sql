-- Every order this character still has something waiting for, newest first. One row is one card.
SELECT id, obj_id, side, quantity, filled_quantity, limit_price, status, slot, owed_items, owed_gp
FROM exchange_orders
WHERE character_id = ? AND (owed_items > 0 OR owed_gp > 0)
ORDER BY id DESC
