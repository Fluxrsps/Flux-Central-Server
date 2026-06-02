SELECT id, account_name AS username, rights, created_at, updated_at
FROM accounts
WHERE id = ?
