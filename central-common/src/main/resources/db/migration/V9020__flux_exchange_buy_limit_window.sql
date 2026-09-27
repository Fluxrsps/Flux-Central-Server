-- The buy limit is a rolling window that starts at a character's first purchase of an item and
-- resets, all at once, when it ends. The hourly buckets it replaced approximated that badly: a buy
-- at 10:45 sat in the 10:00 bucket, which fell out of a four-hour lookback at 14:00, so the whole
-- allowance came back up to an hour early.
--
-- One row per (character, item): when the current window opened and how much has filled in it.
-- A fill that lands after the window has closed starts a new one rather than adding to the old.
CREATE TABLE IF NOT EXISTS exchange_buy_limit_window (
    character_id    INTEGER NOT NULL,
    obj_id          INTEGER NOT NULL,
    window_start    TIMESTAMPTZ NOT NULL,
    filled          BIGINT  NOT NULL,
    PRIMARY KEY (character_id, obj_id),
    CONSTRAINT chk_exchange_buy_limit_window_filled CHECK (filled > 0)
);

-- The bucket table stays for now so the previous build still runs against this schema. Nothing
-- reads it once this migration's code is live; open windows are not carried across, which means
-- one forgiven partial window at rollout rather than a guessed conversion.
