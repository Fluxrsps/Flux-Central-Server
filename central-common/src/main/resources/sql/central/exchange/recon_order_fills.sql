-- An order's filled_quantity must be exactly the sum of its trades.
WITH filled AS (
    SELECT buy_order_id AS order_id, sum(quantity) AS q FROM exchange_trades WHERE buy_order_id IS NOT NULL GROUP BY 1
    UNION ALL
    SELECT sell_order_id, sum(quantity) FROM exchange_trades WHERE sell_order_id IS NOT NULL GROUP BY 1
)
SELECT o.id, o.obj_id, o.filled_quantity, COALESCE(f.q, 0)
FROM exchange_orders o
LEFT JOIN filled f ON f.order_id = o.id
WHERE o.filled_quantity <> COALESCE(f.q, 0)
