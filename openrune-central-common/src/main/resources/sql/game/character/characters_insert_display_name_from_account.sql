INSERT INTO account_characters (account_id, display_name)
VALUES (?, (SELECT account_name FROM accounts WHERE id = ?))
