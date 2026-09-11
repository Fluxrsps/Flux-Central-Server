-- Transitions between obj identities.
--
-- Kept apart from economy_events because it is a different shape: no actor and no value, just the
-- relationship between two identities. Answers "where did this pile come from" for fungible goods,
-- which identity on its own cannot.
CREATE TABLE IF NOT EXISTS economy_lineage (
    id          BIGSERIAL PRIMARY KEY,
    occurred_at BIGINT  NOT NULL,
    world       INTEGER NOT NULL,
    kind        TEXT    NOT NULL,
    parent_uid  BIGINT  NOT NULL,
    child_uid   BIGINT  NOT NULL,
    obj_id      INTEGER NOT NULL,
    count       INTEGER NOT NULL
);

-- Walking the graph backwards is the common direction: given a pile, what fed it.
CREATE INDEX IF NOT EXISTS idx_economy_lineage_child
    ON economy_lineage (child_uid, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_economy_lineage_parent
    ON economy_lineage (parent_uid, occurred_at DESC);
