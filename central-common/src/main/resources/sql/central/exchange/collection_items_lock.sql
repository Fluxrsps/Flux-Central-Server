SELECT count
FROM exchange_collection_items
WHERE character_id = ? AND obj_id = ?
FOR UPDATE
