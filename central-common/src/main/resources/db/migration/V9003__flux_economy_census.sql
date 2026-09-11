-- Periodic total of every item held by every character.
--
-- The event log records value moving; this records value existing. Comparing consecutive censuses
-- against the events between them answers the question the log alone cannot: whether more of an
-- item exists than was ever legitimately created.
CREATE TABLE IF NOT EXISTS economy_census (
    id           BIGSERIAL PRIMARY KEY,
    taken_at     BIGINT  NOT NULL,
    obj_key      TEXT    NOT NULL,
    total_count  BIGINT  NOT NULL,
    holder_count INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_economy_census_taken
    ON economy_census (taken_at DESC);

CREATE INDEX IF NOT EXISTS idx_economy_census_obj
    ON economy_census (obj_key, taken_at DESC);
