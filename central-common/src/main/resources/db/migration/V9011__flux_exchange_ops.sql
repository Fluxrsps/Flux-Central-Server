-- Trading Post: integrity, operations and launch liquidity.
--
-- Flags and alerts never change prices or orders; they feed a staff queue. System liquidity is
-- built here but every row starts disabled. Notifications are rows first and pushes second, so a
-- player who was offline still receives them at login.

CREATE TABLE IF NOT EXISTS exchange_flags (
    id                  BIGSERIAL PRIMARY KEY,
    kind                TEXT    NOT NULL,
    trade_id            BIGINT,
    order_id            BIGINT,
    obj_id              INTEGER,
    character_id        INTEGER,
    counterparty_id     INTEGER,
    details             JSONB   NOT NULL DEFAULT '{}'::jsonb,
    status              TEXT    NOT NULL DEFAULT 'OPEN',
    reviewed_by         INTEGER,
    review_note         TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at         TIMESTAMPTZ,
    CONSTRAINT fk_exchange_flags_trade
        FOREIGN KEY (trade_id) REFERENCES exchange_trades (id) ON DELETE RESTRICT,
    CONSTRAINT chk_exchange_flags_kind CHECK (
        kind IN (
            'PRICE_FAR_FROM_MARKET', 'REPEATED_PAIRING', 'LINKED_ACCOUNTS', 'WASH_TRADING',
            'CORNERING', 'RAPID_CANCEL_REPLACE', 'RATE_LIMITED', 'INVALID_INPUT_PROBING',
            'SELF_TRADE_ATTEMPT'
        )
    ),
    CONSTRAINT chk_exchange_flags_status CHECK (status IN ('OPEN', 'REVIEWED', 'DISMISSED')),
    CONSTRAINT chk_exchange_flags_reviewed CHECK ((status = 'OPEN') = (reviewed_at IS NULL))
);

CREATE INDEX IF NOT EXISTS idx_exchange_flags_open
    ON exchange_flags (created_at DESC) WHERE status = 'OPEN';
CREATE INDEX IF NOT EXISTS idx_exchange_flags_trade
    ON exchange_flags (trade_id) WHERE trade_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_exchange_flags_character
    ON exchange_flags (character_id, created_at DESC) WHERE character_id IS NOT NULL;

-- One open alert per (kind, item) until resolved; the partial unique index is the de-duplication.
CREATE TABLE IF NOT EXISTS exchange_alerts (
    id              BIGSERIAL PRIMARY KEY,
    kind            TEXT    NOT NULL,
    severity        TEXT    NOT NULL DEFAULT 'WARN',
    obj_id          INTEGER NOT NULL DEFAULT 0,
    details         JSONB   NOT NULL DEFAULT '{}'::jsonb,
    status          TEXT    NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_by     INTEGER,
    resolved_at     TIMESTAMPTZ,
    CONSTRAINT chk_exchange_alerts_kind CHECK (
        kind IN (
            'PRICE_CHANGE_24H', 'PRICE_CHANGE_7D', 'VOLUME_CHANGE', 'SUSPICIOUS_PATTERN',
            'SINK_FAUCET_IMBALANCE', 'RECONCILIATION', 'SETTLEMENT_LATENCY', 'RETRY_RATE',
            'SYSTEM_NET_GP', 'NEW_ITEM_WATCH', 'COLLECTION_BOX_FULL'
        )
    ),
    CONSTRAINT chk_exchange_alerts_severity CHECK (severity IN ('INFO', 'WARN', 'CRITICAL')),
    CONSTRAINT chk_exchange_alerts_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT chk_exchange_alerts_resolved CHECK ((status = 'OPEN') = (resolved_at IS NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_exchange_alerts_open
    ON exchange_alerts (kind, obj_id)
    WHERE status = 'OPEN';

CREATE INDEX IF NOT EXISTS idx_exchange_alerts_open_time
    ON exchange_alerts (created_at DESC) WHERE status = 'OPEN';

-- Launch liquidity config, per item. A SYSTEM BUY is a GP faucet: the check below refuses a
-- spread that could price it at or above the item's alch or shop value.
CREATE TABLE IF NOT EXISTS exchange_system_liquidity (
    obj_id                      INTEGER PRIMARY KEY,
    enabled                     BOOLEAN NOT NULL DEFAULT FALSE,
    buy_enabled                 BOOLEAN NOT NULL DEFAULT TRUE,
    sell_enabled                BOOLEAN NOT NULL DEFAULT TRUE,
    buy_spread_bps              INTEGER NOT NULL DEFAULT 1500,
    sell_spread_bps             INTEGER NOT NULL DEFAULT 1500,
    sell_floor                  BIGINT  NOT NULL DEFAULT 1,
    hourly_cap_buy              BIGINT  NOT NULL DEFAULT 0,
    daily_cap_buy               BIGINT  NOT NULL DEFAULT 0,
    hourly_cap_sell             BIGINT  NOT NULL DEFAULT 0,
    daily_cap_sell              BIGINT  NOT NULL DEFAULT 0,
    per_account_daily_cap       BIGINT  NOT NULL DEFAULT 0,
    wind_down_days              INTEGER NOT NULL DEFAULT 7,
    player_volume_threshold_bps INTEGER NOT NULL DEFAULT 5000,
    consecutive_days_above      INTEGER NOT NULL DEFAULT 0,
    pinned                      BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by                  INTEGER,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_exchange_system_liquidity_item
        FOREIGN KEY (obj_id) REFERENCES exchange_items (obj_id) ON DELETE RESTRICT,
    CONSTRAINT chk_exchange_system_liquidity_spreads CHECK (
        buy_spread_bps BETWEEN 1 AND 9999 AND sell_spread_bps BETWEEN 1 AND 100000
    ),
    CONSTRAINT chk_exchange_system_liquidity_caps CHECK (
        sell_floor >= 1 AND hourly_cap_buy >= 0 AND daily_cap_buy >= 0
        AND hourly_cap_sell >= 0 AND daily_cap_sell >= 0 AND per_account_daily_cap >= 0
        AND wind_down_days >= 1 AND player_volume_threshold_bps BETWEEN 0 AND 10000
        AND consecutive_days_above >= 0
    )
);

-- Filled quantity against the system per item, side, hour and character. character_id 0 is the
-- item-wide row; caps are sums over this table inside the fill transaction.
CREATE TABLE IF NOT EXISTS exchange_system_usage (
    obj_id          INTEGER NOT NULL,
    side            TEXT    NOT NULL,
    bucket_start    TIMESTAMPTZ NOT NULL,
    character_id    INTEGER NOT NULL DEFAULT 0,
    filled          BIGINT  NOT NULL,
    gp              BIGINT  NOT NULL,
    PRIMARY KEY (obj_id, side, bucket_start, character_id),
    CONSTRAINT chk_exchange_system_usage_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT chk_exchange_system_usage_values CHECK (filled > 0 AND gp >= 0)
);

CREATE INDEX IF NOT EXISTS idx_exchange_system_usage_bucket
    ON exchange_system_usage (bucket_start);

CREATE TABLE IF NOT EXISTS exchange_notifications (
    id              BIGSERIAL PRIMARY KEY,
    character_id    INTEGER NOT NULL,
    kind            TEXT    NOT NULL,
    order_id        BIGINT,
    obj_id          INTEGER,
    payload         JSONB   NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at    TIMESTAMPTZ,
    delivered_world INTEGER,
    CONSTRAINT fk_exchange_notifications_character
        FOREIGN KEY (character_id) REFERENCES account_characters (id) ON DELETE CASCADE,
    CONSTRAINT chk_exchange_notifications_kind CHECK (
        kind IN (
            'ORDER_FILLED', 'ORDER_PARTIALLY_FILLED', 'ORDER_EXPIRED', 'ORDER_CANCELLED_SYSTEM',
            'COLLECTION_BOX_NEARLY_FULL', 'TRADING_CLOSING', 'CLAIM_AVAILABLE'
        )
    )
);

CREATE INDEX IF NOT EXISTS idx_exchange_notifications_undelivered
    ON exchange_notifications (character_id, created_at)
    WHERE delivered_at IS NULL;

-- Central listens on exchange_notifications and pushes the row to the world the character is on.
CREATE OR REPLACE FUNCTION exchange_notifications_notify_fn() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify(
        'exchange_notifications',
        json_build_object(
            'id', NEW.id,
            'character_id', NEW.character_id,
            'kind', NEW.kind,
            'order_id', NEW.order_id,
            'obj_id', NEW.obj_id
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER exchange_notifications_notify
AFTER INSERT ON exchange_notifications
FOR EACH ROW
EXECUTE PROCEDURE exchange_notifications_notify_fn();

-- Compensating transactions for confirmed exploits. History is never deleted: a reversal is a
-- new linked row plus TRADE_REVERSED ledger events, and the trade keeps its reversed_at stamp.
CREATE TABLE IF NOT EXISTS exchange_reversals (
    id                  BIGSERIAL PRIMARY KEY,
    trade_id            BIGINT  NOT NULL,
    staff_character_id  INTEGER NOT NULL,
    reason              TEXT    NOT NULL,
    dry_run             BOOLEAN NOT NULL,
    correlation_id      UUID    NOT NULL,
    plan                JSONB   NOT NULL DEFAULT '{}'::jsonb,
    shortfall           JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at          TIMESTAMPTZ,
    CONSTRAINT fk_exchange_reversals_trade
        FOREIGN KEY (trade_id) REFERENCES exchange_trades (id) ON DELETE RESTRICT,
    CONSTRAINT chk_exchange_reversals_applied CHECK (dry_run OR applied_at IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_exchange_reversals_applied
    ON exchange_reversals (trade_id)
    WHERE NOT dry_run;

CREATE OR REPLACE TRIGGER exchange_reversals_append_only
BEFORE UPDATE OR DELETE ON exchange_reversals
FOR EACH ROW
EXECUTE PROCEDURE exchange_append_only_fn();

-- Shortfalls a reversal could not recover, carried as debt until staff settle them.
CREATE TABLE IF NOT EXISTS exchange_debts (
    id                  BIGSERIAL PRIMARY KEY,
    character_id        INTEGER NOT NULL,
    reversal_id         BIGINT  NOT NULL,
    obj_id              INTEGER,
    count               BIGINT  NOT NULL DEFAULT 0,
    gp                  BIGINT  NOT NULL DEFAULT 0,
    status              TEXT    NOT NULL DEFAULT 'OPEN',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_by          INTEGER,
    settled_at          TIMESTAMPTZ,
    CONSTRAINT fk_exchange_debts_reversal
        FOREIGN KEY (reversal_id) REFERENCES exchange_reversals (id) ON DELETE RESTRICT,
    CONSTRAINT chk_exchange_debts_amounts CHECK (count >= 0 AND gp >= 0 AND (count > 0 OR gp > 0)),
    CONSTRAINT chk_exchange_debts_status CHECK (status IN ('OPEN', 'SETTLED', 'WRITTEN_OFF')),
    CONSTRAINT chk_exchange_debts_settled CHECK ((status = 'OPEN') = (settled_at IS NULL))
);

CREATE INDEX IF NOT EXISTS idx_exchange_debts_open
    ON exchange_debts (character_id) WHERE status = 'OPEN';

CREATE TABLE IF NOT EXISTS exchange_economy_snapshots (
    day                     DATE    PRIMARY KEY,
    gp_in_buy_reservations  BIGINT  NOT NULL,
    items_in_sell_reservations BIGINT NOT NULL,
    gp_in_collection        BIGINT  NOT NULL,
    items_in_collection     BIGINT  NOT NULL,
    tax_sunk_total          BIGINT  NOT NULL,
    tax_sunk_day            BIGINT  NOT NULL,
    system_gp_paid_day      BIGINT  NOT NULL,
    system_gp_taken_day     BIGINT  NOT NULL,
    active_traders_day      INTEGER NOT NULL,
    active_orders           INTEGER NOT NULL,
    price_index             NUMERIC(24, 6),
    details                 JSONB   NOT NULL DEFAULT '{}'::jsonb,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Per-character rate-limit and probing counters that outlive a single world session. Small and
-- rewritten in place; the flags table holds the durable record when a limit trips.
CREATE TABLE IF NOT EXISTS exchange_account_state (
    character_id            INTEGER PRIMARY KEY,
    invalid_input_count     INTEGER NOT NULL DEFAULT 0,
    rate_limit_trips        INTEGER NOT NULL DEFAULT 0,
    last_rejected_at        TIMESTAMPTZ,
    first_trade_at          TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_exchange_account_state_character
        FOREIGN KEY (character_id) REFERENCES account_characters (id) ON DELETE CASCADE
);
