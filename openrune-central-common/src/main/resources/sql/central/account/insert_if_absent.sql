INSERT INTO accounts (login_username, password_hash, rights, created_at, updated_at)
VALUES (?, ?, '', ?, ?)
ON CONFLICT ((lower(login_username))) DO NOTHING
