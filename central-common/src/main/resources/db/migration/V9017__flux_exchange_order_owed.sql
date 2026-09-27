-- What each order still has waiting in the collection box.
--
-- The box itself stays pooled - exchange_collection_items is keyed by (character, obj) and
-- exchange_collection_gp by character - because that is what custody and reconciliation are built
-- on. What it could never answer is which order a pile came from, so an interface showing one card
-- per order had to guess, and with several finished orders on one item it guessed wrong: a pile
-- would be drawn against whichever order happened to look plausible, and a cancelled order owed
-- only coins would draw a card for the item it never received.
--
-- These two columns are the attribution the pool cannot hold. Every credit that puts something in
-- the box adds it here against the order that produced it, and every claim takes it off the same
-- order, so the pool stays the sum of its parts:
--
--   sum(owed_items) per (character_id, obj_id) = exchange_collection_items.count
--   sum(owed_gp)    per character_id           = exchange_collection_gp.amount
--
-- A claim in flight is out of both sides at once, so the identity holds across the two-step.
ALTER TABLE exchange_orders
    ADD COLUMN IF NOT EXISTS owed_items BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS owed_gp    BIGINT NOT NULL DEFAULT 0;

ALTER TABLE exchange_orders
    DROP CONSTRAINT IF EXISTS chk_exchange_orders_owed;

ALTER TABLE exchange_orders
    ADD CONSTRAINT chk_exchange_orders_owed CHECK (owed_items >= 0 AND owed_gp >= 0);

-- Claims have to know which order they came out of, so releasing one puts it back where it was
-- rather than onto whatever order looks likely.
ALTER TABLE exchange_claims
    ADD COLUMN IF NOT EXISTS order_id BIGINT;

-- Balances that predate the columns cannot be attributed from the data - the pool never recorded
-- where they came from. Rather than strand them, each pile goes to the newest terminal order that
-- could have produced it, which is the same guess the interface was making and is now the last time
-- anything guesses. Anything left over stays in the pool, collectable by the sweep buttons but
-- without a card of its own.
WITH newest_item_order AS (
    SELECT DISTINCT ON (o.character_id, o.obj_id) o.id, o.character_id, o.obj_id
    FROM exchange_orders o
    WHERE o.status IN ('FILLED', 'CANCELLED', 'EXPIRED')
    ORDER BY o.character_id, o.obj_id, o.id DESC
)
UPDATE exchange_orders o
SET owed_items = c.count
FROM exchange_collection_items c
JOIN newest_item_order n ON n.character_id = c.character_id AND n.obj_id = c.obj_id
WHERE o.id = n.id;

WITH newest_order AS (
    SELECT DISTINCT ON (o.character_id) o.id, o.character_id
    FROM exchange_orders o
    WHERE o.status IN ('FILLED', 'CANCELLED', 'EXPIRED')
    ORDER BY o.character_id, o.id DESC
)
UPDATE exchange_orders o
SET owed_gp = g.amount
FROM exchange_collection_gp g
JOIN newest_order n ON n.character_id = g.character_id
WHERE o.id = n.id;
