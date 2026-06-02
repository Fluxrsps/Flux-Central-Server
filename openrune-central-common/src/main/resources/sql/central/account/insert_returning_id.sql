INSERT INTO accounts (account_name, password_hash, rights, created_at, updated_at)
VALUES (?, ?, ?, ?, ?)
RETURNING id
