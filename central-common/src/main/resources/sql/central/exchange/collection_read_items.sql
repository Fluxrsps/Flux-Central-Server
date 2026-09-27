SELECT obj_id, count
FROM exchange_collection_items
WHERE character_id = ?
ORDER BY obj_id
