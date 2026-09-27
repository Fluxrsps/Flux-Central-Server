UPDATE exchange_collection_gp
SET amount = amount - ?, updated_at = CURRENT_TIMESTAMP
WHERE character_id = ? AND amount > ?
