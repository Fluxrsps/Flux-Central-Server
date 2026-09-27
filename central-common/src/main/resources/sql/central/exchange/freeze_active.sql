-- Any freeze that applies to this item or character. GLOBAL beats ITEM beats ACCOUNT when several
-- are live, so the caller can report the broadest reason.
SELECT scope
FROM exchange_freezes
WHERE lifted_at IS NULL
  AND (
      scope = 'GLOBAL'
      OR (scope = 'ITEM' AND target_id = ?)
      OR (scope = 'ACCOUNT' AND target_id = ?)
  )
ORDER BY CASE scope WHEN 'GLOBAL' THEN 0 WHEN 'ITEM' THEN 1 ELSE 2 END
LIMIT 1
