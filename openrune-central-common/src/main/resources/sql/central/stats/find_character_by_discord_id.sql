SELECT c.id AS character_id,
       c.display_name,
       a.account_name,
       COALESCE(
           (SELECT MAX(s.updated_at) FROM stats s WHERE s.character_id = c.id),
           TIMESTAMP '1970-01-01'
       ) AS stats_updated_at
FROM accounts a
JOIN account_characters c ON c.account_id = a.id
WHERE a.discord_id = ?
ORDER BY c.id ASC
LIMIT 1
