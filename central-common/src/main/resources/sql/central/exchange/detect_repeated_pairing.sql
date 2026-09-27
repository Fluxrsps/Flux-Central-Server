-- The same two accounts trading with each other far more than chance allows: at least ? trades
-- that are at least ? bps of everything one of them did in the window.
WITH window_trades AS (
    SELECT buyer_character_id AS b, seller_character_id AS s
    FROM exchange_trades
    WHERE executed_at > ? AND source = 'PLAYER'
),
pairs AS (
    SELECT least(b, s) AS a, greatest(b, s) AS c, count(*) AS n
    FROM window_trades
    GROUP BY 1, 2
),
totals AS (
    SELECT x.c AS who, count(*) AS n
    FROM (SELECT b AS c FROM window_trades UNION ALL SELECT s FROM window_trades) x
    GROUP BY x.c
)
SELECT p.a, p.c, p.n, ta.n AS a_total, tc.n AS c_total
FROM pairs p
JOIN totals ta ON ta.who = p.a
JOIN totals tc ON tc.who = p.c
WHERE p.n >= ?
  AND (p.n * 10000 >= ta.n * ? OR p.n * 10000 >= tc.n * ?)
  AND NOT EXISTS (
      SELECT 1 FROM exchange_flags f
      WHERE f.kind = 'REPEATED_PAIRING' AND f.status = 'OPEN'
        AND f.character_id = p.a AND f.counterparty_id = p.c
  )
