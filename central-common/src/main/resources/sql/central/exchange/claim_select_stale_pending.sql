SELECT id
FROM exchange_claims
WHERE character_id = ?
  AND status = 'PENDING'
  AND created_at <= ?
ORDER BY id
