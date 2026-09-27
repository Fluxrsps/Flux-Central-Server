-- The trade window for one item. Reversed trades never count; the calculator does the rest of
-- the cleaning because it needs per-trade prices and parties.
SELECT buyer_character_id, seller_character_id, quantity, unit_price, source, flags, executed_at
FROM exchange_trades
WHERE obj_id = ?
  AND executed_at > ?
  AND reversed_at IS NULL
ORDER BY executed_at
