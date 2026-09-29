-- Fluxious admin panel: live rights/donator-rank/game-mode pushes, plus a one-shot command
-- mailbox for actions with no persistent state column (currently just "teleport home"). Mirrors
-- the account_characters_notify_mute_fn / punishments_notify_kick_fn pattern in V04 and V08:
-- an AFTER trigger NOTIFYs, Central's PgNotifyService relays it to whichever world the account is
-- on over the world-link socket.

CREATE OR REPLACE FUNCTION accounts_notify_rights_fn() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.rights IS NOT DISTINCT FROM OLD.rights THEN
        RETURN NEW;
    END IF;
    PERFORM pg_notify(
        'account_rights_events',
        json_build_object(
            'account_id', NEW.id,
            'rights', COALESCE(NEW.rights, '')
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS accounts_notify_rights ON accounts;
CREATE TRIGGER accounts_notify_rights
AFTER INSERT OR UPDATE OF rights ON accounts
FOR EACH ROW
EXECUTE PROCEDURE accounts_notify_rights_fn();

CREATE OR REPLACE FUNCTION account_characters_notify_donator_rank_fn() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.donator_rank IS NOT DISTINCT FROM OLD.donator_rank THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.donator_rank IS NULL THEN
        RETURN NEW;
    END IF;
    PERFORM pg_notify(
        'character_donator_rank_events',
        json_build_object(
            'account_id', NEW.account_id,
            'character_id', NEW.id,
            'donator_rank', COALESCE(NEW.donator_rank, 'NONE')
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS account_characters_notify_donator_rank ON account_characters;
CREATE TRIGGER account_characters_notify_donator_rank
AFTER INSERT OR UPDATE OF donator_rank ON account_characters
FOR EACH ROW
EXECUTE PROCEDURE account_characters_notify_donator_rank_fn();

CREATE OR REPLACE FUNCTION account_characters_notify_game_mode_fn() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.game_mode IS NOT DISTINCT FROM OLD.game_mode THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.game_mode IS NULL THEN
        RETURN NEW;
    END IF;
    PERFORM pg_notify(
        'character_game_mode_events',
        json_build_object(
            'account_id', NEW.account_id,
            'character_id', NEW.id,
            'game_mode', COALESCE(NEW.game_mode, 'ADVENTURER')
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS account_characters_notify_game_mode ON account_characters;
CREATE TRIGGER account_characters_notify_game_mode
AFTER INSERT OR UPDATE OF game_mode ON account_characters
FOR EACH ROW
EXECUTE PROCEDURE account_characters_notify_game_mode_fn();

-- One-shot admin command mailbox (e.g. "send this stuck player home"). Website inserts a row,
-- this trigger NOTIFYs, Central relays it once. No status/consumption tracking: these are
-- fire-and-forget, exactly like world_reboot_schedules/world_broadcast_log rows are for worlds.
CREATE TABLE IF NOT EXISTS player_admin_commands (
    id BIGSERIAL PRIMARY KEY,
    character_id INTEGER NOT NULL REFERENCES account_characters (id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    created_by TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_player_admin_commands_kind CHECK (kind IN ('teleport_home'))
);

CREATE INDEX IF NOT EXISTS idx_player_admin_commands_character
    ON player_admin_commands (character_id, created_at DESC);

CREATE OR REPLACE FUNCTION player_admin_commands_notify_fn() RETURNS trigger AS $$
DECLARE
    acc_id bigint;
BEGIN
    acc_id := (SELECT ac.account_id FROM account_characters ac WHERE ac.id = NEW.character_id);
    IF acc_id IS NULL THEN
        RETURN NEW;
    END IF;
    PERFORM pg_notify(
        'player_admin_command_events',
        json_build_object(
            'account_id', acc_id,
            'character_id', NEW.character_id,
            'kind', NEW.kind
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS player_admin_commands_notify ON player_admin_commands;
CREATE TRIGGER player_admin_commands_notify
AFTER INSERT ON player_admin_commands
FOR EACH ROW
EXECUTE PROCEDURE player_admin_commands_notify_fn();
