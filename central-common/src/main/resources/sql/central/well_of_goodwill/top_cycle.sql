-- Leaderboard for one cycle, highest first.
SELECT display_name, amount
FROM well_of_goodwill_donations
WHERE cycle_id = ? AND amount > 0
ORDER BY amount DESC, display_name ASC
LIMIT ?
