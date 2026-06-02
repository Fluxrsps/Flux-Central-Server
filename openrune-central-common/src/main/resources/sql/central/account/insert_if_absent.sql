INSERT INTO accounts (account_name, password_hash, rights, created_at, updated_at)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT ((lower(account_name))) DO NOTHING
