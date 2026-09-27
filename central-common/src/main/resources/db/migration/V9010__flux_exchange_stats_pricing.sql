-- Trading Post: statistics and the Fluxious market price.
--
-- Nothing on a request path scans raw trades. Settlement upserts the hourly bucket in its own
-- transaction; a Central job rolls hours into days and recomputes the market price from the
-- buckets. Volume is split three ways (player / system / seeded) so public figures can be built
-- from real trades only whatever the config says.

CREATE TABLE IF NOT EXISTS exchange_stats_hourly (
    obj_id              INTEGER NOT NULL,
    bucket_start        TIMESTAMPTZ NOT NULL,
    open_price          BIGINT  NOT NULL,
    close_price         BIGINT  NOT NULL,
    low_price           BIGINT  NOT NULL,
    high_price          BIGINT  NOT NULL,
    -- VWAP is kept as numerator and denominator so buckets merge exactly.
    vwap_numerator      NUMERIC(38, 0) NOT NULL DEFAULT 0,
    vwap_denominator    BIGINT  NOT NULL DEFAULT 0,
    volume              BIGINT  NOT NULL DEFAULT 0,
    transaction_count   INTEGER NOT NULL DEFAULT 0,
    player_volume       BIGINT  NOT NULL DEFAULT 0,
    system_volume       BIGINT  NOT NULL DEFAULT 0,
    seeded_volume       BIGINT  NOT NULL DEFAULT 0,
    tax_collected       BIGINT  NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (obj_id, bucket_start),
    CONSTRAINT chk_exchange_stats_hourly_prices CHECK (
        low_price >= 1 AND low_price <= high_price
        AND open_price BETWEEN low_price AND high_price
        AND close_price BETWEEN low_price AND high_price
    ),
    CONSTRAINT chk_exchange_stats_hourly_volume CHECK (
        volume >= 0 AND transaction_count >= 0 AND tax_collected >= 0
        AND player_volume >= 0 AND system_volume >= 0 AND seeded_volume >= 0
        AND volume = player_volume + system_volume + seeded_volume
        AND vwap_denominator = volume
    )
);

CREATE INDEX IF NOT EXISTS idx_exchange_stats_hourly_bucket
    ON exchange_stats_hourly (bucket_start DESC);

-- Distinct traders cannot be summed across buckets, so they are counted per hour into a side
-- table the rollup and price jobs read. One row per (item, hour, character, side).
CREATE TABLE IF NOT EXISTS exchange_stats_hourly_traders (
    obj_id          INTEGER NOT NULL,
    bucket_start    TIMESTAMPTZ NOT NULL,
    character_id    INTEGER NOT NULL,
    side            TEXT    NOT NULL,
    volume          BIGINT  NOT NULL,
    PRIMARY KEY (obj_id, bucket_start, character_id, side),
    CONSTRAINT chk_exchange_stats_hourly_traders_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT chk_exchange_stats_hourly_traders_volume CHECK (volume > 0)
);

CREATE TABLE IF NOT EXISTS exchange_stats_daily (
    obj_id              INTEGER NOT NULL,
    day                 DATE    NOT NULL,
    open_price          BIGINT  NOT NULL,
    close_price         BIGINT  NOT NULL,
    low_price           BIGINT  NOT NULL,
    high_price          BIGINT  NOT NULL,
    vwap_numerator      NUMERIC(38, 0) NOT NULL DEFAULT 0,
    vwap_denominator    BIGINT  NOT NULL DEFAULT 0,
    volume              BIGINT  NOT NULL DEFAULT 0,
    transaction_count   INTEGER NOT NULL DEFAULT 0,
    distinct_buyers     INTEGER NOT NULL DEFAULT 0,
    distinct_sellers    INTEGER NOT NULL DEFAULT 0,
    player_volume       BIGINT  NOT NULL DEFAULT 0,
    system_volume       BIGINT  NOT NULL DEFAULT 0,
    seeded_volume       BIGINT  NOT NULL DEFAULT 0,
    tax_collected       BIGINT  NOT NULL DEFAULT 0,
    rolled_up_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (obj_id, day),
    CONSTRAINT chk_exchange_stats_daily_prices CHECK (
        low_price >= 1 AND low_price <= high_price
        AND open_price BETWEEN low_price AND high_price
        AND close_price BETWEEN low_price AND high_price
    ),
    CONSTRAINT chk_exchange_stats_daily_volume CHECK (
        volume >= 0 AND transaction_count >= 0 AND tax_collected >= 0
        AND distinct_buyers >= 0 AND distinct_sellers >= 0
        AND volume = player_volume + system_volume + seeded_volume
        AND vwap_denominator = volume
    )
);

CREATE INDEX IF NOT EXISTS idx_exchange_stats_daily_day
    ON exchange_stats_daily (day DESC);

-- The live Fluxious market price, one row per item. stable_price is the 24h median and is the
-- only column any other system may consume; the live price is display and analytics only.
CREATE TABLE IF NOT EXISTS exchange_market_prices (
    obj_id          INTEGER PRIMARY KEY,
    price           BIGINT  NOT NULL,
    stable_price    BIGINT  NOT NULL,
    confidence      NUMERIC(6, 5) NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_exchange_market_prices_item
        FOREIGN KEY (obj_id) REFERENCES exchange_items (obj_id) ON DELETE RESTRICT,
    CONSTRAINT chk_exchange_market_prices_price CHECK (price >= 1 AND stable_price >= 1),
    CONSTRAINT chk_exchange_market_prices_confidence CHECK (confidence >= 0 AND confidence <= 1)
);

-- Every recalculation with its inputs, so staff can see why a price moved. Append-only.
CREATE TABLE IF NOT EXISTS exchange_market_price_history (
    id                  BIGSERIAL PRIMARY KEY,
    obj_id              INTEGER NOT NULL,
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    old_price           BIGINT  NOT NULL,
    new_price           BIGINT  NOT NULL,
    vwap                NUMERIC(24, 6),
    pressure            NUMERIC(12, 8),
    confidence          NUMERIC(6, 5) NOT NULL,
    anchor_weight       NUMERIC(6, 5) NOT NULL,
    anchor_price        BIGINT,
    market_target       NUMERIC(24, 6),
    anchored_target     NUMERIC(24, 6),
    max_change          NUMERIC(6, 5) NOT NULL,
    clamped             BOOLEAN NOT NULL DEFAULT FALSE,
    trades_used         INTEGER NOT NULL DEFAULT 0,
    trades_excluded     INTEGER NOT NULL DEFAULT 0,
    distinct_buyers     INTEGER NOT NULL DEFAULT 0,
    distinct_sellers    INTEGER NOT NULL DEFAULT 0,
    buy_depth           BIGINT  NOT NULL DEFAULT 0,
    sell_depth          BIGINT  NOT NULL DEFAULT 0,
    seeded_weight       NUMERIC(6, 5) NOT NULL DEFAULT 0,
    reason              TEXT    NOT NULL DEFAULT 'SCHEDULED',
    staff_character_id  INTEGER,
    CONSTRAINT chk_exchange_market_price_history_prices CHECK (old_price >= 1 AND new_price >= 1),
    CONSTRAINT chk_exchange_market_price_history_reason CHECK (
        reason IN ('SCHEDULED', 'INITIAL_BASE_PRICE', 'STAFF_OVERRIDE', 'REVERSAL_RECOMPUTE')
    )
);

CREATE INDEX IF NOT EXISTS idx_exchange_market_price_history_item
    ON exchange_market_price_history (obj_id, computed_at DESC);

CREATE OR REPLACE TRIGGER exchange_market_price_history_append_only
BEFORE UPDATE OR DELETE ON exchange_market_price_history
FOR EACH ROW
EXECUTE PROCEDURE exchange_append_only_fn();
