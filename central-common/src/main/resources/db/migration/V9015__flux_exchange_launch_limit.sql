-- New-item release protocol: a lower buy limit for the first stretch after launch.
ALTER TABLE exchange_items
    ADD COLUMN IF NOT EXISTS launch_buy_limit INTEGER,
    ADD CONSTRAINT chk_exchange_items_launch_buy_limit CHECK (launch_buy_limit IS NULL OR launch_buy_limit > 0);
