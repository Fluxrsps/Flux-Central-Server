-- Character: donator + game mode (stored as enum level). Account: rights as Rights.level integer.
ALTER TABLE account_characters
    ADD COLUMN IF NOT EXISTS donator_rank INTEGER NOT NULL DEFAULT 0;

ALTER TABLE account_characters
    ADD COLUMN IF NOT EXISTS game_mode INTEGER NOT NULL DEFAULT 0;

ALTER TABLE account_characters
    DROP COLUMN IF EXISTS rights;

-- Migrate legacy TEXT modlevel.* account rights to integer levels (Rights.level).
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
            AND table_name = 'accounts'
            AND column_name = 'rights'
            AND data_type = 'text'
    ) THEN
        ALTER TABLE accounts
            ALTER COLUMN rights DROP DEFAULT;

        ALTER TABLE accounts
            ALTER COLUMN rights TYPE INTEGER
            USING (
                CASE
                    WHEN rights IS NULL OR btrim(rights) = '' THEN 0
                    WHEN lower(rights) LIKE '%modlevel.owner%' THEN 5
                    WHEN lower(rights) LIKE '%modlevel.dev%' THEN 4
                    WHEN lower(rights) LIKE '%modlevel.admin%' THEN 3
                    WHEN lower(rights) LIKE '%modlevel.moderator%' THEN 1
                    WHEN lower(rights) LIKE '%modlevel.player%' THEN 0
                    ELSE 0
                END
            );

        ALTER TABLE accounts
            ALTER COLUMN rights SET DEFAULT 0;

        ALTER TABLE accounts
            ALTER COLUMN rights SET NOT NULL;
    END IF;
END $$;
