-- Empties the pot and moves to the next cycle. Donation history is kept for the all-time board.
UPDATE well_of_goodwill_state
SET cycle_id = cycle_id + 1, total = 0, updated_at = CURRENT_TIMESTAMP
WHERE id = TRUE
RETURNING cycle_id, total
