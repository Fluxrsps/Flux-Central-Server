-- Caller has locked the row and checked the balance. A row can't hold zero (CHECK count > 0), so
-- an exact debit is a delete; see collection_debit_items_exact.sql.
UPDATE exchange_collection_items
SET count = count - ?, updated_at = CURRENT_TIMESTAMP
WHERE character_id = ? AND obj_id = ? AND count > ?
