-- Which individual obj an event concerned. Zero for stackables and for anything created before
-- identities existed, so the column is only meaningful where it is non-zero.
ALTER TABLE economy_events
    ADD COLUMN IF NOT EXISTS obj_uid BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_economy_events_uid
    ON economy_events (obj_uid, occurred_at) WHERE obj_uid <> 0;
