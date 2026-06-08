-- Ensure Laravel Fortify 2FA columns exist (fresh installs already have them from 03_accounts.sql).
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS two_factor_secret TEXT;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS two_factor_recovery_codes TEXT;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS two_factor_confirmed_at TIMESTAMP;

-- Copy legacy RSPS twofa_* columns into Laravel two_factor_* when present.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = ANY (current_schemas(true))
            AND table_name = 'accounts'
            AND column_name = 'twofa_secret'
    ) THEN
        UPDATE accounts
        SET two_factor_secret = twofa_secret
        WHERE two_factor_secret IS NULL
            AND twofa_secret IS NOT NULL;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = ANY (current_schemas(true))
            AND table_name = 'accounts'
            AND column_name = 'twofa_recovery_codes'
    ) THEN
        UPDATE accounts
        SET two_factor_recovery_codes = twofa_recovery_codes
        WHERE two_factor_recovery_codes IS NULL
            AND twofa_recovery_codes IS NOT NULL;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = ANY (current_schemas(true))
            AND table_name = 'accounts'
            AND column_name = 'twofa_confirmed_at'
    ) THEN
        UPDATE accounts
        SET two_factor_confirmed_at = twofa_confirmed_at
        WHERE two_factor_confirmed_at IS NULL
            AND twofa_confirmed_at IS NOT NULL;
    ELSIF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = ANY (current_schemas(true))
            AND table_name = 'accounts'
            AND column_name = 'twofa_last_verified'
    ) THEN
        UPDATE accounts
        SET two_factor_confirmed_at = twofa_last_verified
        WHERE two_factor_confirmed_at IS NULL
            AND twofa_last_verified IS NOT NULL;
    END IF;
END
$$;
