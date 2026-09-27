-- Trading Post two-step markers on the character row, saved with the rest of the player profile.
-- An order id here means the world took the player's assets for it and saved; a claim id means the
-- world handed the assets over and saved. Login recovery reads these to decide refund vs drop.
ALTER TABLE account_characters
    ADD COLUMN IF NOT EXISTS trading_post_escrow_orders BIGINT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS trading_post_pending_claims BIGINT[] NOT NULL DEFAULT '{}';
