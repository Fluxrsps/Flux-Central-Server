CREATE TABLE IF NOT EXISTS character_snapshots (
    id           BIGSERIAL PRIMARY KEY,
    character_id INTEGER NOT NULL,
    account_id   INTEGER NOT NULL,
    taken_at     BIGINT  NOT NULL,
    reason       TEXT    NOT NULL,
    payload      JSONB   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_character_snapshots_char_taken
    ON character_snapshots (character_id, taken_at DESC);
