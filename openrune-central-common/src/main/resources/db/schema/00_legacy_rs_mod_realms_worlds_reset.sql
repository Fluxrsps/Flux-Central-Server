-- One-time migration for databases that still have the old RS Mod default realm only.
-- When matched, drops the entire public schema (all tables, functions, etc.) so the rest of
-- db/schema/*.sql recreates everything on this bootstrap pass.
--
-- Match: realm_id = 1, name = 'default', description = 'Default realm',
--        login_message = 'Welcome to RS Mod.', and realms is the only row.
-- Skips fresh installs (no realms table) and already-migrated DBs (no legacy row).

DO $legacy_rs_mod_realms_worlds_reset$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
            AND table_name = 'realms'
    ) THEN
        RETURN;
    END IF;

    IF (SELECT COUNT(*)::bigint FROM realms) <> 1 THEN
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM realms AS r
        WHERE r.realm_id = 1
            AND r.name = 'default'
            AND r.description = 'Default realm'
            AND r.login_message = 'Welcome to RS Mod.'
    ) THEN
        RETURN;
    END IF;

    DROP SCHEMA public CASCADE;
    CREATE SCHEMA public;
    GRANT ALL ON SCHEMA public TO current_user;
    GRANT USAGE ON SCHEMA public TO public;
END;
$legacy_rs_mod_realms_worlds_reset$ LANGUAGE plpgsql;
