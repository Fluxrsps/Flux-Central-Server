-- Where one character sits on the all-time board. Counting the rows above beats a window function
-- over the whole table, and the index on total_votes serves it.
SELECT
    (SELECT COUNT(*) + 1 FROM vote_totals t2 WHERE t2.total_votes > t1.total_votes) AS rank,
    t1.total_votes
FROM vote_totals t1
WHERE t1.character_id = ?
