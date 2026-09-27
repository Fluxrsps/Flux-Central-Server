INSERT INTO exchange_collection_items (character_id, obj_id, count)
VALUES (?, ?, ?)
ON CONFLICT (character_id, obj_id)
DO UPDATE SET count = exchange_collection_items.count + EXCLUDED.count, updated_at = CURRENT_TIMESTAMP
