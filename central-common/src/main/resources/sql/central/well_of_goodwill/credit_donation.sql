-- Adds to a character's tally for the cycle, creating it on their first donation of that cycle.
INSERT INTO well_of_goodwill_donations (cycle_id, character_id, display_name, amount)
VALUES (?, ?, ?, ?)
ON CONFLICT (cycle_id, character_id) DO UPDATE
SET amount = well_of_goodwill_donations.amount + EXCLUDED.amount,
    display_name = EXCLUDED.display_name,
    updated_at = CURRENT_TIMESTAMP
