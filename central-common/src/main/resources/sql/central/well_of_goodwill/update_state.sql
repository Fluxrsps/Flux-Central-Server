-- Writes the pot back after a donation has been applied.
UPDATE well_of_goodwill_state
SET cycle_id = ?, total = ?, updated_at = CURRENT_TIMESTAMP
WHERE id = TRUE
