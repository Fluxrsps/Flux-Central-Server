SELECT 1
FROM punishments p
JOIN account_characters ac ON ac.id = ?
WHERE p.status = 'active'
  AND p.kind IN ('ban', 'temp_ban', 'locked')
  AND (p.expires_at IS NULL OR p.expires_at > CURRENT_TIMESTAMP)
  AND (
      (p.scope = 'character' AND p.character_id = ac.id)
      OR (p.scope = 'account' AND p.account_id = ac.account_id)
  )
LIMIT 1
