-- A player's own recent orders with what they paid in tax and the average price they got. No
-- counterparties anywhere in this query, by design.
SELECT o.id, o.side, o.obj_id, o.quantity, o.filled_quantity, o.limit_price, o.status, o.created_at,
       (SELECT COALESCE(sum(t.tax), 0) FROM exchange_trades t WHERE t.sell_order_id = o.id) AS tax_paid,
       (SELECT CASE WHEN sum(t.quantity) > 0 THEN sum(t.gross_value) / sum(t.quantity) END
          FROM exchange_trades t WHERE t.buy_order_id = o.id OR t.sell_order_id = o.id) AS avg_price
FROM exchange_orders o
WHERE o.character_id = ?
ORDER BY o.created_at DESC, o.id DESC
LIMIT ?
