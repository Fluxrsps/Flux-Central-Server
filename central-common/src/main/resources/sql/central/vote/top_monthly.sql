-- Leaderboard for one month, highest first.
SELECT display_name, votes
FROM vote_monthly
WHERE period = ? AND votes > 0
ORDER BY votes DESC, display_name ASC
LIMIT ?
