-- Identity for individual objs. Zero means untracked, which is what every row predating this
-- column is: existing items keep working, they simply cannot be followed backwards in time.
ALTER TABLE inventory_objs
    ADD COLUMN IF NOT EXISTS uid BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_inventory_objs_uid
    ON inventory_objs (uid) WHERE uid <> 0;
