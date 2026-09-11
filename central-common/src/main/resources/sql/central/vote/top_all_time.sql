-- Leaderboard across every character, highest first.
SELECT display_name, total_votes
FROM vote_totals
WHERE total_votes > 0
ORDER BY total_votes DESC, display_name ASC
LIMIT ?
