ALTER TABLE accounts ADD COLUMN IF NOT EXISTS discord_id TEXT;

CREATE INDEX IF NOT EXISTS idx_accounts_discord_id ON accounts (discord_id) WHERE discord_id IS NOT NULL;
