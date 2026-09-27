UPDATE exchange_trades
SET reversed_at = ?
WHERE id = ? AND reversed_at IS NULL
