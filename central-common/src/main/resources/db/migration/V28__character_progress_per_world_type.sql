/* Splits a character into identity and progress.

   A character is a named persona - an account may hold several - and it spans every world type. Its
   identity, display name and social graph therefore stay in `public`, shared by all modes, and keep
   their foreign keys.

   What varies per world type is the save: position, levels, items, varps, attrs, run energy, xp
   rate. Those move into a schema per mode, one `character_progress` row per character per mode, so
   logging into leagues loads a different save for the same character. `main` is created here because
   every world serves it; event schemas are created by the game server from its `game.yml`. */

DO $relocate$
BEGIN
    IF to_regclass('public.character_varps') IS NULL THEN
        RETURN;
    END IF;

    CREATE SCHEMA IF NOT EXISTS main;

    ALTER TABLE public.character_varps SET SCHEMA main;
    ALTER TABLE public.character_attrs SET SCHEMA main;
    ALTER TABLE public.stats SET SCHEMA main;
    ALTER TABLE public.inventories SET SCHEMA main;
    ALTER TABLE public.inventory_objs SET SCHEMA main;

    CREATE TABLE IF NOT EXISTS main.character_progress (
        character_id INTEGER PRIMARY KEY
            REFERENCES public.account_characters (id) ON DELETE CASCADE,
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

    -- Saves made before world types existed are `main` saves.
    INSERT INTO main.character_progress (
        character_id, world_id, x, z, level, last_login, last_logout,
        run_energy, xp_rate_in_hundreds, online_central_world_id, online_session_heartbeat
    )
    SELECT id, world_id, x, z, level, last_login, last_logout,
           run_energy, xp_rate_in_hundreds, online_central_world_id, online_session_heartbeat
    FROM public.account_characters
    ON CONFLICT (character_id) DO NOTHING;
END
$relocate$;

/* Identity keeps display name, membership and moderation state; the save columns are now per mode.
   `members`, `muted_until` and `banned_until` stay here deliberately - they describe the person, not
   one mode's progress. */
ALTER TABLE account_characters DROP COLUMN IF EXISTS world_id;
ALTER TABLE account_characters DROP COLUMN IF EXISTS x;
ALTER TABLE account_characters DROP COLUMN IF EXISTS z;
ALTER TABLE account_characters DROP COLUMN IF EXISTS level;
ALTER TABLE account_characters DROP COLUMN IF EXISTS last_login;
ALTER TABLE account_characters DROP COLUMN IF EXISTS last_logout;
ALTER TABLE account_characters DROP COLUMN IF EXISTS run_energy;
ALTER TABLE account_characters DROP COLUMN IF EXISTS xp_rate_in_hundreds;
ALTER TABLE account_characters DROP COLUMN IF EXISTS online_central_world_id;
ALTER TABLE account_characters DROP COLUMN IF EXISTS online_session_heartbeat;
