-- order_select_owed runs on every window open and every refresh, and asks for a character's orders
-- that are still owed something. The existing (character_id, status) index narrows it to the
-- character but not to the handful of rows that actually carry a balance, so on a character with a
-- long history it walks every order they ever placed. A partial index over exactly that predicate
-- keeps it to the rows the query returns.
CREATE INDEX IF NOT EXISTS idx_exchange_orders_owed
    ON exchange_orders (character_id)
    WHERE owed_items > 0 OR owed_gp > 0;
