SELECT id
FROM account_characters
WHERE LOWER(display_name) = LOWER(?)
LIMIT 1
