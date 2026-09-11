CREATE TABLE IF NOT EXISTS vote_totals (
    character_id INTEGER PRIMARY KEY,
    display_name TEXT NOT NULL,
    total_votes INTEGER NOT NULL DEFAULT 0,
    last_vote_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (character_id) REFERENCES account_characters (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_vote_totals_total ON vote_totals (total_votes DESC);

CREATE TABLE IF NOT EXISTS vote_monthly (
    character_id INTEGER NOT NULL,
    period CHAR(7) NOT NULL,
    display_name TEXT NOT NULL,
    votes INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (character_id, period),
    FOREIGN KEY (character_id) REFERENCES account_characters (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_vote_monthly_period_votes ON vote_monthly (period, votes DESC);
