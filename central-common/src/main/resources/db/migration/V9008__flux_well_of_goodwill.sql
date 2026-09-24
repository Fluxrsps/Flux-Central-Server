-- Well of Goodwill.
--
-- One shared pot across every world. The game owns both tables: it takes the donations, crosses the
-- milestones and starts the next cycle. Nothing here is written by the website.

-- The pot itself, a single row. cycle_id increments each time the goal is reached, which is what
-- separates "this cycle" from the all-time history without deleting anything.
CREATE TABLE IF NOT EXISTS well_of_goodwill_state (
    id BOOLEAN PRIMARY KEY DEFAULT TRUE,
    cycle_id BIGINT NOT NULL DEFAULT 1,
    total BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT well_of_goodwill_state_single_row CHECK (id)
);

INSERT INTO well_of_goodwill_state (id) VALUES (TRUE) ON CONFLICT (id) DO NOTHING;

-- A running tally per character per cycle rather than a row per donation: every question asked of
-- it is a sum or a rank, and the all-time board is the same table summed across cycles.
CREATE TABLE IF NOT EXISTS well_of_goodwill_donations (
    cycle_id BIGINT NOT NULL,
    character_id INTEGER NOT NULL,
    display_name TEXT NOT NULL,
    amount BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (cycle_id, character_id),
    FOREIGN KEY (character_id) REFERENCES account_characters (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_wog_donations_cycle
    ON well_of_goodwill_donations (cycle_id, amount DESC);

CREATE INDEX IF NOT EXISTS idx_wog_donations_character
    ON well_of_goodwill_donations (character_id);
