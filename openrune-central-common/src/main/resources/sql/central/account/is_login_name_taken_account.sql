SELECT 1
FROM accounts
WHERE LOWER(account_name) = LOWER(?) AND id != ?
LIMIT 1
