SELECT 1
FROM world_login_whitelist
WHERE world_id = ?
  AND lower(account_name) = lower(?)
LIMIT 1
