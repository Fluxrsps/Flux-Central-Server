-- A character's donated total across every cycle, and the rank that goes with it. Rank is zero when
-- they have never donated, so the interface can say so rather than claiming first place.
SELECT d.total AS amount,
       CASE
           WHEN d.total = 0 THEN 0
           ELSE (SELECT COUNT(*) + 1
                 FROM (SELECT SUM(amount) AS s
                       FROM well_of_goodwill_donations
                       GROUP BY character_id) o
                 WHERE o.s > d.total)
       END AS rank
FROM (SELECT COALESCE(SUM(amount), 0) AS total
      FROM well_of_goodwill_donations
      WHERE character_id = ?) d
