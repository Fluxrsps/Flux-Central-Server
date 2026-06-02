SELECT id, account_name AS username, rights, created_at, updated_at
FROM accounts
WHERE account_name LIKE ? ESCAPE '\'
ORDER BY id DESC
LIMIT ? OFFSET ?
