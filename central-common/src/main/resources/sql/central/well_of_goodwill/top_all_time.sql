-- Leaderboard across every cycle, highest first.
SELECT MAX(display_name) AS display_name, SUM(amount) AS total
FROM well_of_goodwill_donations
GROUP BY character_id
HAVING SUM(amount) > 0
ORDER BY total DESC, display_name ASC
LIMIT ?
