SELECT character_id, obj_id, owed_items, owed_gp
FROM exchange_orders
WHERE id = ?
FOR UPDATE
