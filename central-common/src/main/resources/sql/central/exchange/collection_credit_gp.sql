INSERT INTO exchange_collection_gp (character_id, amount)
VALUES (?, ?)
ON CONFLICT (character_id)
DO UPDATE SET amount = exchange_collection_gp.amount + EXCLUDED.amount, updated_at = CURRENT_TIMESTAMP
