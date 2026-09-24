-- A character's donated total for one cycle, and the rank that goes with it. Rank is zero when they
-- have not donated this cycle, so the interface can say so rather than claiming first place.
SELECT d.total AS amount,
       CASE
           WHEN d.total = 0 THEN 0
           ELSE (SELECT COUNT(*) + 1
                 FROM well_of_goodwill_donations o
                 WHERE o.cycle_id = ? AND o.amount > d.total)
       END AS rank
FROM (SELECT COALESCE(SUM(amount), 0) AS total
      FROM well_of_goodwill_donations
      WHERE cycle_id = ? AND character_id = ?) d
