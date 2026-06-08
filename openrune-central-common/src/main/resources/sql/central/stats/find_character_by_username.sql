SELECT c.id AS character_id,
       c.display_name,
       a.account_name,
       COALESCE(
           (SELECT MAX(s.updated_at) FROM stats s WHERE s.character_id = c.id),
           TIMESTAMP '1970-01-01'
       ) AS stats_updated_at
FROM account_characters c
JOIN accounts a ON a.id = c.account_id
WHERE LOWER(TRIM(c.display_name)) = LOWER(TRIM(?))
   OR LOWER(TRIM(a.account_name)) = LOWER(TRIM(?))
ORDER BY
    CASE WHEN LOWER(TRIM(c.display_name)) = LOWER(TRIM(?)) THEN 0 ELSE 1 END,
    c.id ASC
LIMIT 1
