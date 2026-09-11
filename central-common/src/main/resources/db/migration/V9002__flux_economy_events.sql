CREATE TABLE IF NOT EXISTS economy_events (
    id                        BIGSERIAL PRIMARY KEY,
    occurred_at               BIGINT  NOT NULL,
    world                     INTEGER NOT NULL,
    kind                      TEXT    NOT NULL,
    actor_character_id        INTEGER NOT NULL,
    actor_name                TEXT    NOT NULL,
    counterparty_character_id INTEGER,
    counterparty_name         TEXT,
    obj_id                    INTEGER NOT NULL,
    obj_name                  TEXT    NOT NULL,
    count                     INTEGER NOT NULL,
    unit_value                INTEGER NOT NULL,
    total_value               BIGINT  NOT NULL,
    coord_x                   INTEGER NOT NULL DEFAULT 0,
    coord_z                   INTEGER NOT NULL DEFAULT 0,
    coord_level               INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_economy_events_occurred
    ON economy_events (occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_economy_events_actor
    ON economy_events (actor_character_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_economy_events_obj
    ON economy_events (obj_id, occurred_at DESC);

-- Pairing a ground drop with the pickup that follows it needs place as well as time; item id and
-- timing alone cannot tell two people dropping the same item apart.
CREATE INDEX IF NOT EXISTS idx_economy_events_where
    ON economy_events (obj_id, coord_x, coord_z, occurred_at);
