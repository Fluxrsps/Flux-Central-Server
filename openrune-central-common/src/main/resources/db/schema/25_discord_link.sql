CREATE TABLE IF NOT EXISTS discord_link_pending (
    account_id INTEGER PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
    discord_user_id TEXT NOT NULL,
    code INTEGER NOT NULL,
    wrong_attempts INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_discord_link_pending_discord_user
    ON discord_link_pending (discord_user_id);

CREATE INDEX IF NOT EXISTS idx_discord_link_pending_expires ON discord_link_pending (expires_at);

CREATE OR REPLACE FUNCTION accounts_notify_discord_id_fn() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.discord_id IS NOT DISTINCT FROM OLD.discord_id THEN
        RETURN NEW;
    END IF;
    PERFORM pg_notify(
        'account_discord_id_events',
        json_build_object(
            'account_id', NEW.id::text,
            'discord_id', COALESCE(NEW.discord_id, '')
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS accounts_notify_discord_id ON accounts;
CREATE TRIGGER accounts_notify_discord_id
AFTER INSERT OR UPDATE OF discord_id ON accounts
FOR EACH ROW
EXECUTE PROCEDURE accounts_notify_discord_id_fn();
