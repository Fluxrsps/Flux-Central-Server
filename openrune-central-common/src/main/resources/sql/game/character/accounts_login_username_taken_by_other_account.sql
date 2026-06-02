SELECT 1 AS taken
FROM accounts a
WHERE LOWER(TRIM(a.account_name)) = LOWER(TRIM(?))
  AND a.id <> ?
LIMIT 1
