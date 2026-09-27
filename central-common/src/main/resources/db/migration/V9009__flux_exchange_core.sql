-- Trading Post: core exchange.
--
-- Orders, reservations, trades, the collection box, claims, buy-limit usage, the append-only
-- ledger, config and freezes. Every world and Central share these tables; the engine that writes
-- them lives in central-common so both sides run the same code. Player identity is the
-- account_characters id, matching every other per-player table. GP is BIGINT everywhere.
--
-- The constraints here are the last line of defence against duplication and loss: even a buggy
-- caller cannot overfill an order, leave a reservation out of step with its remainder, move a
-- terminal order, or rewrite the ledger.

-- Per-item exchange metadata, upserted by the game server from its price table and the cache at
-- startup. base_price is the anchor the market-price algorithm leans on while confidence is low
-- and the market price an item starts at; the sync only fills base_price and buy_limit while
-- they are null, so a staff edit is never overwritten. Tradeability is not stored: the game
-- server checks the cache before an order reaches here.
CREATE TABLE IF NOT EXISTS exchange_items (
    obj_id                  INTEGER PRIMARY KEY,
    frozen                  BOOLEAN NOT NULL DEFAULT FALSE,
    launch_state            TEXT    NOT NULL DEFAULT 'NORMAL',
    base_price              BIGINT,
    buy_limit               INTEGER,
    high_alch_value         BIGINT  NOT NULL DEFAULT 0,
    shop_sell_value         BIGINT  NOT NULL DEFAULT 0,
    tax_category            TEXT    NOT NULL DEFAULT 'REGULAR',
    tax_exempt              BOOLEAN NOT NULL DEFAULT FALSE,
    launch_limit_until      TIMESTAMPTZ,
    metadata_synced_at      TIMESTAMPTZ,
    CONSTRAINT chk_exchange_items_launch_state CHECK (launch_state IN ('NORMAL', 'DISCOVERY')),
    CONSTRAINT chk_exchange_items_tax_category CHECK (
        tax_category IN ('REGULAR', 'HIGH_VALUE', 'DONATOR_ADVERTISEMENT', 'PROMOTED_LISTING')
    ),
    CONSTRAINT chk_exchange_items_buy_limit CHECK (buy_limit IS NULL OR buy_limit > 0),
    CONSTRAINT chk_exchange_items_base_price CHECK (base_price IS NULL OR base_price >= 1)
);

CREATE TABLE IF NOT EXISTS exchange_orders (
    id                  BIGSERIAL PRIMARY KEY,
    character_id        INTEGER NOT NULL,
    obj_id              INTEGER NOT NULL,
    side                TEXT    NOT NULL,
    source              TEXT    NOT NULL DEFAULT 'PLAYER',
    quantity            BIGINT  NOT NULL,
    filled_quantity     BIGINT  NOT NULL DEFAULT 0,
    remaining_quantity  BIGINT  GENERATED ALWAYS AS (quantity - filled_quantity) STORED,
    limit_price         BIGINT  NOT NULL,
    reserved_amount     BIGINT  NOT NULL DEFAULT 0,
    status              TEXT    NOT NULL DEFAULT 'PENDING',
    cancel_reason       TEXT,
    client_request_id   TEXT    NOT NULL,
    correlation_id      UUID    NOT NULL,
    world               INTEGER NOT NULL DEFAULT 0,
    created_by          INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    opened_at           TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ,
    CONSTRAINT fk_exchange_orders_character
        FOREIGN KEY (character_id) REFERENCES account_characters (id) ON DELETE RESTRICT,
    CONSTRAINT fk_exchange_orders_item
        FOREIGN KEY (obj_id) REFERENCES exchange_items (obj_id) ON DELETE RESTRICT,
    CONSTRAINT uq_exchange_orders_request UNIQUE (character_id, client_request_id),
    CONSTRAINT chk_exchange_orders_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT chk_exchange_orders_source CHECK (source IN ('PLAYER', 'SYSTEM', 'ADMIN')),
    CONSTRAINT chk_exchange_orders_status CHECK (
        status IN ('PENDING', 'OPEN', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT chk_exchange_orders_cancel_reason CHECK (
        (status = 'CANCELLED') = (cancel_reason IS NOT NULL)
        AND (cancel_reason IS NULL
             OR cancel_reason IN ('PLAYER', 'BANNED', 'SYSTEM_FAILURE', 'FROZEN', 'DELISTED'))
    ),
    CONSTRAINT chk_exchange_orders_quantity CHECK (quantity > 0),
    CONSTRAINT chk_exchange_orders_limit_price CHECK (limit_price >= 1),
    CONSTRAINT chk_exchange_orders_filled CHECK (filled_quantity >= 0 AND filled_quantity <= quantity),
    CONSTRAINT chk_exchange_orders_reserved_non_negative CHECK (reserved_amount >= 0),
    -- The reservation invariant, held by the database after every statement.
    CONSTRAINT chk_exchange_orders_reservation CHECK (
        CASE status
            WHEN 'OPEN' THEN reserved_amount =
                CASE side WHEN 'BUY' THEN (quantity - filled_quantity) * limit_price
                          ELSE (quantity - filled_quantity) END
            WHEN 'PARTIALLY_FILLED' THEN reserved_amount =
                CASE side WHEN 'BUY' THEN (quantity - filled_quantity) * limit_price
                          ELSE (quantity - filled_quantity) END
            ELSE reserved_amount = 0
        END
    ),
    CONSTRAINT chk_exchange_orders_status_fill CHECK (
        (status <> 'FILLED' OR filled_quantity = quantity)
        AND (status <> 'PARTIALLY_FILLED' OR (filled_quantity > 0 AND filled_quantity < quantity))
        AND (status NOT IN ('PENDING', 'OPEN') OR filled_quantity = 0)
    ),
    CONSTRAINT chk_exchange_orders_terminal_stamps CHECK (
        (status <> 'FILLED' OR completed_at IS NOT NULL)
        AND (status NOT IN ('CANCELLED', 'EXPIRED') OR cancelled_at IS NOT NULL)
    )
);

-- The book scans. Each side gets its own partial index in the exact order the matcher asks for
-- (best price first, then oldest), so the LIMIT n FOR UPDATE SKIP LOCKED scan reads the first n
-- entries and stops. A BUY scan is price DESC then time ASC, which one shared index cannot serve.
CREATE INDEX IF NOT EXISTS idx_exchange_orders_book_sell
    ON exchange_orders (obj_id, limit_price ASC, created_at ASC, id ASC)
    WHERE side = 'SELL' AND status IN ('OPEN', 'PARTIALLY_FILLED');

CREATE INDEX IF NOT EXISTS idx_exchange_orders_book_buy
    ON exchange_orders (obj_id, limit_price DESC, created_at ASC, id ASC)
    WHERE side = 'BUY' AND status IN ('OPEN', 'PARTIALLY_FILLED');

CREATE INDEX IF NOT EXISTS idx_exchange_orders_character
    ON exchange_orders (character_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_exchange_orders_expiry
    ON exchange_orders (expires_at)
    WHERE expires_at IS NOT NULL AND status IN ('OPEN', 'PARTIALLY_FILLED');

CREATE INDEX IF NOT EXISTS idx_exchange_orders_pending
    ON exchange_orders (created_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_exchange_orders_correlation
    ON exchange_orders (correlation_id);

-- The order state machine. Terminal rows never change again, and the only ways forward are the
-- ones the spec draws. Everything else is a bug in the caller and is refused here.
CREATE OR REPLACE FUNCTION exchange_orders_guard_transition_fn() RETURNS trigger AS $$
BEGIN
    IF OLD.status IN ('FILLED', 'CANCELLED', 'EXPIRED') THEN
        RAISE EXCEPTION 'exchange order % is terminal (%), no further updates allowed',
            OLD.id, OLD.status USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.id <> OLD.id OR NEW.character_id <> OLD.character_id OR NEW.obj_id <> OLD.obj_id
        OR NEW.side <> OLD.side OR NEW.source <> OLD.source OR NEW.quantity <> OLD.quantity
        OR NEW.limit_price <> OLD.limit_price OR NEW.client_request_id <> OLD.client_request_id
        OR NEW.correlation_id <> OLD.correlation_id OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'exchange order % identity columns are immutable', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.filled_quantity < OLD.filled_quantity THEN
        RAISE EXCEPTION 'exchange order % filled_quantity may not decrease (% -> %)',
            OLD.id, OLD.filled_quantity, NEW.filled_quantity USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status <> OLD.status THEN
        IF NOT (
            (OLD.status = 'PENDING' AND NEW.status IN ('OPEN', 'CANCELLED'))
            OR (OLD.status = 'OPEN' AND NEW.status IN ('PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'EXPIRED'))
            OR (OLD.status = 'PARTIALLY_FILLED' AND NEW.status IN ('FILLED', 'CANCELLED', 'EXPIRED'))
        ) THEN
            RAISE EXCEPTION 'exchange order % illegal transition % -> %',
                OLD.id, OLD.status, NEW.status USING ERRCODE = 'check_violation';
        END IF;
        IF OLD.status = 'PENDING' AND NEW.status = 'CANCELLED' AND NEW.cancel_reason <> 'SYSTEM_FAILURE' THEN
            RAISE EXCEPTION 'exchange order % pending orders may only be cancelled for SYSTEM_FAILURE', OLD.id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER exchange_orders_guard_transition
BEFORE UPDATE ON exchange_orders
FOR EACH ROW
EXECUTE PROCEDURE exchange_orders_guard_transition_fn();

CREATE OR REPLACE FUNCTION exchange_orders_forbid_delete_fn() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'exchange orders are never deleted (order %)', OLD.id
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER exchange_orders_forbid_delete
BEFORE DELETE ON exchange_orders
FOR EACH ROW
EXECUTE PROCEDURE exchange_orders_forbid_delete_fn();

-- One row per fill. SEEDED rows carry no orders and no parties: they exist only to make launch
-- charts look populated and are excluded from conservation checks by construction.
CREATE TABLE IF NOT EXISTS exchange_trades (
    id                      BIGSERIAL PRIMARY KEY,
    buy_order_id            BIGINT,
    sell_order_id           BIGINT,
    buyer_character_id      INTEGER,
    seller_character_id     INTEGER,
    maker_side              TEXT    NOT NULL,
    obj_id                  INTEGER NOT NULL,
    quantity                BIGINT  NOT NULL,
    unit_price              BIGINT  NOT NULL,
    gross_value             BIGINT  NOT NULL,
    tax_rate_bps            INTEGER NOT NULL,
    tax                     BIGINT  NOT NULL,
    net_value               BIGINT  NOT NULL,
    source                  TEXT    NOT NULL,
    flags                   TEXT[]  NOT NULL DEFAULT '{}',
    buy_filled_before       BIGINT,
    correlation_id          UUID    NOT NULL,
    world                   INTEGER NOT NULL DEFAULT 0,
    reversed_at             TIMESTAMPTZ,
    executed_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_exchange_trades_buy_order
        FOREIGN KEY (buy_order_id) REFERENCES exchange_orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_exchange_trades_sell_order
        FOREIGN KEY (sell_order_id) REFERENCES exchange_orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_exchange_trades_item
        FOREIGN KEY (obj_id) REFERENCES exchange_items (obj_id) ON DELETE RESTRICT,
    -- The same fill can never be recorded twice: a pair of orders at a given buyer fill level
    -- is a single event.
    CONSTRAINT uq_exchange_trades_fill UNIQUE (buy_order_id, sell_order_id, buy_filled_before),
    CONSTRAINT chk_exchange_trades_maker_side CHECK (maker_side IN ('BUY', 'SELL')),
    CONSTRAINT chk_exchange_trades_source CHECK (source IN ('PLAYER', 'SYSTEM', 'ADMIN', 'SEEDED')),
    CONSTRAINT chk_exchange_trades_quantity CHECK (quantity > 0),
    CONSTRAINT chk_exchange_trades_unit_price CHECK (unit_price >= 1),
    CONSTRAINT chk_exchange_trades_tax CHECK (tax_rate_bps >= 0 AND tax >= 0 AND tax <= gross_value),
    CONSTRAINT chk_exchange_trades_values CHECK (
        gross_value = unit_price * quantity AND net_value = gross_value - tax
    ),
    CONSTRAINT chk_exchange_trades_parties CHECK (
        CASE WHEN source = 'SEEDED'
             THEN buy_order_id IS NULL AND sell_order_id IS NULL
                  AND buyer_character_id IS NULL AND seller_character_id IS NULL
                  AND buy_filled_before IS NULL AND tax = 0
             ELSE buy_order_id IS NOT NULL AND sell_order_id IS NOT NULL
                  AND buyer_character_id IS NOT NULL AND seller_character_id IS NOT NULL
                  AND buy_filled_before IS NOT NULL AND buy_order_id <> sell_order_id
        END
    )
);

CREATE INDEX IF NOT EXISTS idx_exchange_trades_item_time
    ON exchange_trades (obj_id, executed_at DESC);

CREATE INDEX IF NOT EXISTS idx_exchange_trades_buyer
    ON exchange_trades (buyer_character_id, executed_at DESC)
    WHERE buyer_character_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_exchange_trades_seller
    ON exchange_trades (seller_character_id, executed_at DESC)
    WHERE seller_character_id IS NOT NULL;

-- Repeated-pairing and far-from-market detection walk the history of one buyer/seller pair.
CREATE INDEX IF NOT EXISTS idx_exchange_trades_pair
    ON exchange_trades (buyer_character_id, seller_character_id, executed_at DESC)
    WHERE buyer_character_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_exchange_trades_buy_order ON exchange_trades (buy_order_id);
CREATE INDEX IF NOT EXISTS idx_exchange_trades_sell_order ON exchange_trades (sell_order_id);
CREATE INDEX IF NOT EXISTS idx_exchange_trades_correlation ON exchange_trades (correlation_id);

-- Trades are history. The only column that may change is reversed_at, set once by a staff reversal.
CREATE OR REPLACE FUNCTION exchange_trades_guard_fn() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.source = 'SEEDED' THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'exchange trades are never deleted (trade %)', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.reversed_at IS NOT NULL OR NEW.reversed_at IS NULL
        OR to_jsonb(NEW) - 'reversed_at' <> to_jsonb(OLD) - 'reversed_at' THEN
        RAISE EXCEPTION 'exchange trade % is immutable apart from a single reversal', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER exchange_trades_guard
BEFORE UPDATE OR DELETE ON exchange_trades
FOR EACH ROW
EXECUTE PROCEDURE exchange_trades_guard_fn();

-- The collection box. Everything the exchange owes a player sits here until claimed: bought
-- items, released reservations and sale proceeds. Settlement therefore never depends on the
-- player being online or having space.
CREATE TABLE IF NOT EXISTS exchange_collection_items (
    character_id    INTEGER NOT NULL,
    obj_id          INTEGER NOT NULL,
    count           BIGINT  NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (character_id, obj_id),
    CONSTRAINT fk_exchange_collection_items_character
        FOREIGN KEY (character_id) REFERENCES account_characters (id) ON DELETE RESTRICT,
    CONSTRAINT chk_exchange_collection_items_count CHECK (count > 0)
);

CREATE TABLE IF NOT EXISTS exchange_collection_gp (
    character_id    INTEGER PRIMARY KEY,
    amount          BIGINT  NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_exchange_collection_gp_character
        FOREIGN KEY (character_id) REFERENCES account_characters (id) ON DELETE RESTRICT,
    CONSTRAINT chk_exchange_collection_gp_amount CHECK (amount > 0)
);

-- Claims are the second two-step flow (collection box -> player). PENDING means the box has been
-- debited in intent but the player may not have received the assets yet; recovery on login
-- resolves it from the player save.
CREATE TABLE IF NOT EXISTS exchange_claims (
    id                  BIGSERIAL PRIMARY KEY,
    character_id        INTEGER NOT NULL,
    status              TEXT    NOT NULL DEFAULT 'PENDING',
    obj_id              INTEGER,
    count               BIGINT  NOT NULL DEFAULT 0,
    gp                  BIGINT  NOT NULL DEFAULT 0,
    client_request_id   TEXT    NOT NULL,
    correlation_id      UUID    NOT NULL,
    world               INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMPTZ,
    CONSTRAINT fk_exchange_claims_character
        FOREIGN KEY (character_id) REFERENCES account_characters (id) ON DELETE RESTRICT,
    CONSTRAINT uq_exchange_claims_request UNIQUE (character_id, client_request_id),
    CONSTRAINT chk_exchange_claims_status CHECK (status IN ('PENDING', 'COMPLETE', 'RELEASED')),
    CONSTRAINT chk_exchange_claims_payload CHECK (
        count >= 0 AND gp >= 0 AND (count > 0 OR gp > 0) AND ((count > 0) = (obj_id IS NOT NULL))
    ),
    CONSTRAINT chk_exchange_claims_completed CHECK ((status = 'PENDING') = (completed_at IS NULL))
);

CREATE INDEX IF NOT EXISTS idx_exchange_claims_pending
    ON exchange_claims (character_id, created_at)
    WHERE status = 'PENDING';

CREATE OR REPLACE FUNCTION exchange_claims_guard_fn() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'exchange claims are never deleted (claim %)', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'exchange claim % is terminal (%)', OLD.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.character_id <> OLD.character_id OR NEW.obj_id IS DISTINCT FROM OLD.obj_id
        OR NEW.count <> OLD.count OR NEW.gp <> OLD.gp OR NEW.client_request_id <> OLD.client_request_id THEN
        RAISE EXCEPTION 'exchange claim % payload is immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER exchange_claims_guard
BEFORE UPDATE OR DELETE ON exchange_claims
FOR EACH ROW
EXECUTE PROCEDURE exchange_claims_guard_fn();

-- Hourly buckets of BUY fills per character per item. The rolling window is the sum of the
-- buckets inside it, updated in the same transaction as the fill.
CREATE TABLE IF NOT EXISTS exchange_buy_limit_usage (
    character_id    INTEGER NOT NULL,
    obj_id          INTEGER NOT NULL,
    bucket_start    TIMESTAMPTZ NOT NULL,
    filled          BIGINT  NOT NULL,
    PRIMARY KEY (character_id, obj_id, bucket_start),
    CONSTRAINT chk_exchange_buy_limit_usage_filled CHECK (filled > 0)
);

CREATE INDEX IF NOT EXISTS idx_exchange_buy_limit_usage_bucket
    ON exchange_buy_limit_usage (bucket_start);

-- The ledger. Append-only: every change to money or items is described here in the same
-- transaction that made it, and nothing here is ever updated or deleted. Enforced by trigger
-- because both processes connect as the schema owner, so privileges cannot do it.
CREATE TABLE IF NOT EXISTS exchange_events (
    id                  BIGSERIAL PRIMARY KEY,
    event_type          TEXT    NOT NULL,
    character_id        INTEGER,
    order_id            BIGINT,
    trade_id            BIGINT,
    claim_id            BIGINT,
    obj_id              INTEGER,
    quantity            BIGINT,
    amount              BIGINT,
    correlation_id      UUID    NOT NULL,
    staff_character_id  INTEGER,
    reason              TEXT,
    world               INTEGER NOT NULL DEFAULT 0,
    metadata            JSONB   NOT NULL DEFAULT '{}'::jsonb,
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_exchange_events_type CHECK (
        event_type IN (
            'ORDER_CREATED', 'ORDER_OPENED', 'BUY_FUNDS_RESERVED', 'SELL_ITEMS_RESERVED',
            'TRADE_EXECUTED', 'ITEM_CREDITED', 'GP_CREDITED', 'TAX_SINKED',
            'RESERVATION_RELEASED', 'ORDER_CANCELLED', 'ORDER_EXPIRED',
            'CLAIM_STARTED', 'COLLECTION_CLAIMED', 'CLAIM_RELEASED',
            'SYSTEM_ORDER_CREATED', 'ADMIN_ACTION', 'CONFIG_CHANGED',
            'MARKET_PRICE_UPDATED', 'TRADE_FLAGGED', 'TRADE_REVERSED',
            'FREEZE_APPLIED', 'FREEZE_LIFTED', 'ITEM_DELISTED', 'BASE_PRICE_SET',
            'SEED_TRADES_CREATED', 'SEED_TRADES_PURGED', 'RECOVERY_ACTION',
            'REQUEST_REJECTED', 'NOTIFICATION_EMITTED'
        )
    ),
    CONSTRAINT chk_exchange_events_staff_reason CHECK (
        staff_character_id IS NULL OR reason IS NOT NULL
    )
);

CREATE INDEX IF NOT EXISTS idx_exchange_events_time ON exchange_events (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_exchange_events_character
    ON exchange_events (character_id, occurred_at DESC) WHERE character_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_exchange_events_order
    ON exchange_events (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_exchange_events_trade
    ON exchange_events (trade_id) WHERE trade_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_exchange_events_obj
    ON exchange_events (obj_id, occurred_at DESC) WHERE obj_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_exchange_events_correlation ON exchange_events (correlation_id);
CREATE INDEX IF NOT EXISTS idx_exchange_events_type_time ON exchange_events (event_type, occurred_at DESC);

CREATE OR REPLACE FUNCTION exchange_append_only_fn() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% refused)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER exchange_events_append_only
BEFORE UPDATE OR DELETE ON exchange_events
FOR EACH ROW
EXECUTE PROCEDURE exchange_append_only_fn();

-- Every economic parameter lives here, not in code. Values are JSON so one table holds ints,
-- lists and curves alike; the service layer validates each key against its schema before writing.
CREATE TABLE IF NOT EXISTS exchange_config (
    key         TEXT    PRIMARY KEY,
    value       JSONB   NOT NULL,
    changed_by  INTEGER,
    reason      TEXT,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS exchange_config_history (
    id          BIGSERIAL PRIMARY KEY,
    key         TEXT    NOT NULL,
    old_value   JSONB,
    new_value   JSONB   NOT NULL,
    changed_by  INTEGER,
    reason      TEXT    NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_exchange_config_history_key
    ON exchange_config_history (key, changed_at DESC);

CREATE OR REPLACE TRIGGER exchange_config_history_append_only
BEFORE UPDATE OR DELETE ON exchange_config_history
FOR EACH ROW
EXECUTE PROCEDURE exchange_append_only_fn();

-- Kill switches. A freeze is a row while lifted_at is null; the create, match and claim paths
-- read them on every request.
CREATE TABLE IF NOT EXISTS exchange_freezes (
    id                  BIGSERIAL PRIMARY KEY,
    scope               TEXT    NOT NULL,
    target_id           BIGINT  NOT NULL DEFAULT 0,
    reason_code         TEXT    NOT NULL,
    reason              TEXT    NOT NULL,
    hold_orders         BOOLEAN NOT NULL DEFAULT TRUE,
    staff_character_id  INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lifted_by           INTEGER,
    lifted_at           TIMESTAMPTZ,
    CONSTRAINT chk_exchange_freezes_scope CHECK (scope IN ('GLOBAL', 'ITEM', 'ACCOUNT')),
    CONSTRAINT chk_exchange_freezes_target CHECK ((scope = 'GLOBAL') = (target_id = 0))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_exchange_freezes_active
    ON exchange_freezes (scope, target_id)
    WHERE lifted_at IS NULL;
