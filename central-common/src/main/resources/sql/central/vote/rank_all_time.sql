-- Where one character sits on the all-time board, and their total.
--
-- Counting the rows above is cheaper here than a window function over the whole table, and the
-- board is small enough that the covering index on total_votes serves it.
SELECT
    (SELECT COUNT(*) + 1 FROM vote_totals t2 WHERE t2.total_votes > t1.total_votes) AS rank,
    t1.total_votes
FROM vote_totals t1
WHERE t1.character_id = ?
