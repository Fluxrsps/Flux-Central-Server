INSERT INTO accounts (account_name, password_hash)
VALUES (?, ?)
ON CONFLICT ((lower(account_name))) DO NOTHING
