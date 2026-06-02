SELECT 1 AS hit
FROM (
    SELECT regexp_replace(lower(trim(account_name)), '[^a-z0-9]+', '', 'g') AS k
    FROM accounts
    WHERE trim(account_name) <> ''
    UNION ALL
    SELECT regexp_replace(lower(trim(display_name)), '[^a-z0-9]+', '', 'g') AS k
    FROM account_characters
    WHERE display_name IS NOT NULL AND trim(display_name) <> ''
) s
WHERE s.k = ? AND length(s.k) > 0
LIMIT 1
