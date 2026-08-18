/* Move email, 2FA, known device from account_characters to accounts. Drop realm_id from account_characters.
   Rename accounts.login_username to account_name. Align world_login_whitelist column name. */ALTER TABLE accounts ADD COLUMN IF NOT EXISTS email TEXT;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS twofa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS twofa_secret TEXT;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS twofa_last_verified TIMESTAMP;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS known_device INTEGER;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = ANY (current_schemas(true))
            AND table_name = 'account_characters'
            AND column_name = 'email'
    ) THEN
        UPDATE accounts a
        SET
            email = s.email,
            twofa_enabled = s.twofa_enabled,
            twofa_secret = s.twofa_secret,
            twofa_last_verified = s.twofa_last_verified,
            known_device = s.known_device
        FROM (
            SELECT DISTINCT ON (account_id)
                account_id,
                email,
                twofa_enabled,
                twofa_secret,
                twofa_last_verified,
                known_device
            FROM account_characters
            ORDER BY account_id, id
        ) s
        WHERE a.id = s.account_id;
    END IF;
END
$$;

ALTER TABLE account_characters DROP COLUMN IF EXISTS email;
ALTER TABLE account_characters DROP COLUMN IF EXISTS twofa_enabled;
ALTER TABLE account_characters DROP COLUMN IF EXISTS twofa_secret;
ALTER TABLE account_characters DROP COLUMN IF EXISTS twofa_last_verified;
ALTER TABLE account_characters DROP COLUMN IF EXISTS known_device;

DROP INDEX IF EXISTS idx_account_characters_account_realm;
ALTER TABLE account_characters DROP COLUMN IF EXISTS realm_id;

DO $rename_accounts$
DECLARE
    sch text;
BEGIN
    FOREACH sch IN ARRAY current_schemas(true)
    LOOP
        IF EXISTS (
            SELECT 1
            FROM information_schema.columns c
            WHERE c.table_schema = sch
              AND c.table_name = 'accounts'
              AND c.column_name = 'login_username'
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I.%I RENAME COLUMN login_username TO account_name',
                sch,
                'accounts'
            );
        END IF;
    END LOOP;
END
$rename_accounts$;

DROP INDEX IF EXISTS uq_accounts_login_username_lower;

DO $idx_accounts$
DECLARE
    sch text;
BEGIN
    FOREACH sch IN ARRAY current_schemas(true)
    LOOP
        IF EXISTS (
            SELECT 1
            FROM information_schema.columns c
            WHERE c.table_schema = sch
              AND c.table_name = 'accounts'
              AND c.column_name = 'account_name'
        )
            AND NOT EXISTS (
                SELECT 1
                FROM pg_indexes i
                WHERE i.schemaname = sch
                  AND i.tablename = 'accounts'
                  AND i.indexname = 'uq_accounts_account_name_lower'
            )
        THEN
            EXECUTE format(
                'CREATE UNIQUE INDEX uq_accounts_account_name_lower ON %I.%I ((lower(account_name)))',
                sch,
                'accounts'
            );
        END IF;
    END LOOP;
END
$idx_accounts$;

DO $rename_whitelist$
DECLARE
    sch text;
BEGIN
    FOREACH sch IN ARRAY current_schemas(true)
    LOOP
        IF EXISTS (
            SELECT 1
            FROM information_schema.columns c
            WHERE c.table_schema = sch
              AND c.table_name = 'world_login_whitelist'
              AND c.column_name = 'login_username'
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I.%I RENAME COLUMN login_username TO account_name',
                sch,
                'world_login_whitelist'
            );
        END IF;
    END LOOP;
END
$rename_whitelist$;

DROP INDEX IF EXISTS uq_world_login_whitelist_world_user_lower;

DO $idx_whitelist$
DECLARE
    sch text;
BEGIN
    FOREACH sch IN ARRAY current_schemas(true)
    LOOP
        IF EXISTS (
            SELECT 1
            FROM information_schema.columns c
            WHERE c.table_schema = sch
              AND c.table_name = 'world_login_whitelist'
              AND c.column_name = 'account_name'
        )
            AND NOT EXISTS (
                SELECT 1
                FROM pg_indexes i
                WHERE i.schemaname = sch
                  AND i.tablename = 'world_login_whitelist'
                  AND i.indexname = 'uq_world_login_whitelist_world_account_lower'
            )
        THEN
            EXECUTE format(
                'CREATE UNIQUE INDEX uq_world_login_whitelist_world_account_lower ON %I.%I (world_id, lower(account_name))',
                sch,
                'world_login_whitelist'
            );
        END IF;
    END LOOP;
END
$idx_whitelist$;
