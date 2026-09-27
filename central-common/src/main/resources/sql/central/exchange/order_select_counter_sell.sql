-- Resting SELL orders an incoming BUY can take: cheapest first, then oldest. Never the buyer's
-- own orders, never another character on the same account, never a frozen account's. SKIP LOCKED
-- means a row another world is settling is simply passed over rather than waited on.
--
-- The character check is kept alongside the account one rather than folded into it: were the
-- owning row ever missing, the NOT EXISTS would match nothing and quietly stop excluding anyone,
-- and silently losing self-trade prevention is not an acceptable way to fail.
SELECT {columns}
FROM exchange_orders o
WHERE o.obj_id = ?
  AND o.side = 'SELL'
  AND o.status IN ('OPEN', 'PARTIALLY_FILLED')
  AND o.character_id <> ?
  AND NOT EXISTS (
      SELECT 1
      FROM account_characters owner
      JOIN account_characters sibling ON sibling.account_id = owner.account_id
      WHERE owner.id = ? AND sibling.id = o.character_id
  )
  AND o.limit_price <= ?
  AND NOT EXISTS (
      SELECT 1 FROM exchange_freezes f
      WHERE f.lifted_at IS NULL AND f.scope = 'ACCOUNT' AND f.target_id = o.character_id
  )
ORDER BY o.limit_price ASC, o.created_at ASC, o.id ASC
LIMIT ?
FOR UPDATE SKIP LOCKED
