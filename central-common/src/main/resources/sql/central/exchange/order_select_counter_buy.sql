-- Resting BUY orders an incoming SELL can take: best price first, then oldest. The exclusions
-- mirror order_select_counter_sell; see the note there on why the character and account checks
-- are both kept.
SELECT {columns}
FROM exchange_orders o
WHERE o.obj_id = ?
  AND o.side = 'BUY'
  AND o.status IN ('OPEN', 'PARTIALLY_FILLED')
  AND o.character_id <> ?
  AND NOT EXISTS (
      SELECT 1
      FROM account_characters owner
      JOIN account_characters sibling ON sibling.account_id = owner.account_id
      WHERE owner.id = ? AND sibling.id = o.character_id
  )
  AND o.limit_price >= ?
  AND NOT EXISTS (
      SELECT 1 FROM exchange_freezes f
      WHERE f.lifted_at IS NULL AND f.scope = 'ACCOUNT' AND f.target_id = o.character_id
  )
ORDER BY o.limit_price DESC, o.created_at ASC, o.id ASC
LIMIT ?
FOR UPDATE SKIP LOCKED
