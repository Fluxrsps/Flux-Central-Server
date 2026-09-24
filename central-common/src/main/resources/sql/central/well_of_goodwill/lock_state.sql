-- The pot, locked for the length of the donation transaction. Whichever world holds this row is the
-- only one that can see a milestone being crossed, so rewards fire exactly once.
SELECT cycle_id, total
FROM well_of_goodwill_state
WHERE id = TRUE
FOR UPDATE
