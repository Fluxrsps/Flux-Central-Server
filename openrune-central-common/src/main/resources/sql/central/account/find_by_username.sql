SELECT a.id, a.account_name AS username, a.password_hash, a.rights
FROM accounts a
WHERE LOWER(a.account_name) = LOWER(?)
