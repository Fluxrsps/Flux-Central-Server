-- Where one character sits on a month's board, and their votes for it.
SELECT
    (SELECT COUNT(*) + 1 FROM vote_monthly m2 WHERE m2.period = m1.period AND m2.votes > m1.votes) AS rank,
    m1.votes
FROM vote_monthly m1
WHERE m1.character_id = ? AND m1.period = ?
