INSERT INTO accounts (login_username, password_hash, rights, created_at, updated_at)
VALUES (?, ?, 0, ?, ?)
RETURNING id
