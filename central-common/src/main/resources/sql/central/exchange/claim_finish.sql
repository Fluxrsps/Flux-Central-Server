UPDATE exchange_claims
SET status = ?, completed_at = CURRENT_TIMESTAMP
WHERE id = ? AND status = 'PENDING'
