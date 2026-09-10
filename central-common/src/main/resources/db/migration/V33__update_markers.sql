-- Website-owned, like the tables in V31: Laravel migrations never run against this
-- database, so central creates it.
--
-- One row per dated thing worth seeing against the metrics - a content update, a
-- hotfix, an event, a maintenance window. Several may share a day, so the date is
-- indexed rather than unique.

CREATE TABLE IF NOT EXISTS update_markers (
    id BIGSERIAL PRIMARY KEY,
    occurred_on DATE NOT NULL,
    title VARCHAR(255) NOT NULL,
    notes TEXT,
    kind VARCHAR(32) NOT NULL,
    user_id INTEGER NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_update_markers_occurred_on ON update_markers (occurred_on);
