-- Stats are per world type, so the gate sums every mode via the cross-mode view.
SELECT COALESCE(SUM(s.base_level), 0)::int AS total_level
FROM stats_all s
INNER JOIN account_characters c ON c.id = s.character_id
WHERE s.character_id = ?
  AND c.account_id = ?
