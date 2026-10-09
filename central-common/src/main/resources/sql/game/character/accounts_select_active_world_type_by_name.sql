SELECT active_world_type
FROM accounts
WHERE LOWER(account_name) = LOWER(?)
