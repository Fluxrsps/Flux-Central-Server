-- Which of the player's offer slots an order was made from. The interface puts an offer back in the
-- slot it came from rather than packing them together, so slot two can hold something while slot
-- one is empty, and the arrangement survives a relog.
--
-- Nullable rather than defaulted: orders made before this column existed, and those placed by the
-- system, liquidity jobs or staff, were never in a slot at all. The interface falls back to the
-- lowest free slot for those.
ALTER TABLE exchange_orders
    ADD COLUMN IF NOT EXISTS slot INTEGER;

ALTER TABLE exchange_orders
    DROP CONSTRAINT IF EXISTS chk_exchange_orders_slot;

ALTER TABLE exchange_orders
    ADD CONSTRAINT chk_exchange_orders_slot CHECK (slot IS NULL OR slot >= 0);
