-- Daily totals of value entering and leaving the game outside the exchange: alchemy coins,
-- shop buys and sells, npc drops, destroys, despawns, staff spawns. economy_events only keeps rows
-- above a value threshold, which hides exactly the bulk activity that moves an economy, so worlds
-- upsert these counters for every event regardless of size. One row per world, day, kind, item.
CREATE TABLE IF NOT EXISTS economy_flow_daily (
    day       DATE    NOT NULL,
    world     INTEGER NOT NULL,
    kind      TEXT    NOT NULL,
    obj_id    INTEGER NOT NULL,
    count     BIGINT  NOT NULL DEFAULT 0,
    gp_value  BIGINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (day, world, kind, obj_id)
);

CREATE INDEX IF NOT EXISTS idx_economy_flow_daily_day ON economy_flow_daily (day DESC, kind);
