/* One world type's player saves. This script runs once per world-type schema, each with its own
   flyway_schema_history, so every mode gets an identical set of tables and the game server can run
   the same queries against any of them by switching `search_path`.

   Character identity, display names and the social graph live in `public.account_characters` and are
   shared by every mode; a character has one `character_progress` row per mode it has played. */

CREATE TABLE IF NOT EXISTS character_progress (
    character_id INTEGER PRIMARY KEY REFERENCES public.account_characters (id) ON DELETE CASCADE,
    world_id INTEGER,
    x INTEGER NOT NULL DEFAULT 3200,
    z INTEGER NOT NULL DEFAULT 3200,
    level INTEGER NOT NULL DEFAULT 0,
    last_login TIMESTAMP,
    last_logout TIMESTAMP,
    run_energy INTEGER NOT NULL DEFAULT 10000,
    xp_rate_in_hundreds INTEGER NOT NULL DEFAULT 100,
    online_central_world_id INTEGER NULL,
    online_session_heartbeat TIMESTAMP NULL
);

CREATE INDEX IF NOT EXISTS idx_character_progress_online_world
    ON character_progress (online_central_world_id)
    WHERE online_central_world_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS character_varps (
    character_id INTEGER NOT NULL REFERENCES public.account_characters (id) ON DELETE CASCADE,
    varp TEXT NOT NULL,
    value INTEGER NOT NULL,
    PRIMARY KEY (character_id, varp)
);

CREATE INDEX IF NOT EXISTS idx_character_varps_character ON character_varps (character_id);

CREATE TABLE IF NOT EXISTS character_attrs (
    character_id INTEGER NOT NULL REFERENCES public.account_characters (id) ON DELETE CASCADE,
    attr TEXT NOT NULL,
    value_json TEXT NOT NULL,
    PRIMARY KEY (character_id, attr)
);

CREATE INDEX IF NOT EXISTS idx_character_attrs_character ON character_attrs (character_id);

CREATE TABLE IF NOT EXISTS stats (
    character_id INTEGER NOT NULL REFERENCES public.account_characters (id) ON DELETE CASCADE,
    stat_id INTEGER NOT NULL,
    vis_level INTEGER NOT NULL,
    base_level INTEGER NOT NULL,
    fine_xp INTEGER NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (character_id, stat_id)
);

CREATE INDEX IF NOT EXISTS idx_stats_character_id ON stats (character_id);

CREATE TABLE IF NOT EXISTS inventories (
    character_id INTEGER NOT NULL REFERENCES public.account_characters (id) ON DELETE CASCADE,
    inv TEXT NOT NULL,
    PRIMARY KEY (character_id, inv)
);

CREATE INDEX IF NOT EXISTS idx_inventories_character_id ON inventories (character_id);

CREATE TABLE IF NOT EXISTS inventory_objs (
    character_id INTEGER NOT NULL,
    inv TEXT NOT NULL,
    slot INTEGER NOT NULL,
    obj TEXT NOT NULL,
    count INTEGER NOT NULL,
    vars INTEGER NOT NULL,
    PRIMARY KEY (character_id, inv, slot),
    FOREIGN KEY (character_id, inv) REFERENCES inventories (character_id, inv) ON DELETE CASCADE
);
