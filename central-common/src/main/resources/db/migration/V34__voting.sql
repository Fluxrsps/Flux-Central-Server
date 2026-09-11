-- Voting.
--
-- The website owns this: it issues the vote links, takes the vote sites' callbacks, and writes
-- every table below. The game reads them, plus the one write that marks rewards claimed.

-- Settings both sides read, so a number that has to agree lives in exactly one place.
--
-- claim_window_days is how far back a claim pays. The website shows what is claimable from it and
-- claim_rewards.sql pays from it, so the two cannot drift.
CREATE TABLE IF NOT EXISTS vote_settings (
    id BOOLEAN PRIMARY KEY DEFAULT TRUE,
    claim_window_days INTEGER NOT NULL DEFAULT 30,
    CONSTRAINT vote_settings_single_row CHECK (id)
);

INSERT INTO vote_settings (id) VALUES (TRUE) ON CONFLICT (id) DO NOTHING;

-- All-time board. A running tally rather than a row per vote, since every question asked of it is
-- a sum or a rank.
CREATE TABLE IF NOT EXISTS vote_totals (
    character_id INTEGER PRIMARY KEY,
    display_name TEXT NOT NULL,
    total_votes INTEGER NOT NULL DEFAULT 0,
    last_vote_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (character_id) REFERENCES account_characters (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_vote_totals_total ON vote_totals (total_votes DESC);

-- Monthly board, partitioned by period so a finished month is never rewritten and the board rolls
-- over without a scheduled reset.
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

-- Streak only. What is owed lives in vote_callbacks, one row per vote, so a backlog can be aged
-- out by date - a count could not say which of its votes were old.
--
-- Streaks count days, not votes: last_vote_day drives the arithmetic, so two votes on one day do
-- not move streak_days. claimed_rewards is the only column the game writes, and only on a claim.
CREATE TABLE IF NOT EXISTS vote_streaks (
    character_id INTEGER PRIMARY KEY,
    display_name TEXT NOT NULL,
    streak_days INTEGER NOT NULL DEFAULT 0,
    best_streak INTEGER NOT NULL DEFAULT 0,
    last_vote_day INTEGER NOT NULL DEFAULT 0,
    claimed_rewards INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (character_id) REFERENCES account_characters (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_vote_streaks_streak ON vote_streaks (streak_days DESC);

-- When each site will next accept a vote. The sites disagree on window length, so a shared
-- per-day rule would throw away legitimate votes. Both the cooldown shown to the player and the
-- gate that stops a replayed callback counting twice.
CREATE TABLE IF NOT EXISTS vote_site_cooldowns (
    character_id INTEGER NOT NULL,
    site VARCHAR(32) NOT NULL,
    last_vote_at TIMESTAMP NOT NULL,
    next_vote_at TIMESTAMP NOT NULL,
    PRIMARY KEY (character_id, site),
    FOREIGN KEY (character_id) REFERENCES account_characters (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_vote_site_cooldowns_next ON vote_site_cooldowns (next_vote_at);

-- Votes handed off to a site, and the ledger of what is owed. A site only echoes back the opaque
-- token we gave it, so the token is what remembers who was voting and where.
--
-- completed_at set with claimed_at still null is a vote waiting to be claimed. site_reset_at
-- records what the site said about its own next window.
CREATE TABLE IF NOT EXISTS vote_callbacks (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(64) NOT NULL UNIQUE,
    character_id INTEGER NOT NULL,
    display_name TEXT NOT NULL,
    site VARCHAR(32) NOT NULL,
    requested_ip VARCHAR(45),
    callback_ip VARCHAR(45),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    claimed_at TIMESTAMP,
    site_reset_at TIMESTAMP,
    FOREIGN KEY (character_id) REFERENCES account_characters (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_vote_callbacks_completed
    ON vote_callbacks (character_id, site, completed_at DESC);

-- Serves the "what does this character have waiting" lookup, which runs on every page load and
-- every vote panel open.
CREATE INDEX IF NOT EXISTS idx_vote_callbacks_pending
    ON vote_callbacks (character_id, completed_at)
    WHERE completed_at IS NOT NULL AND claimed_at IS NULL;
