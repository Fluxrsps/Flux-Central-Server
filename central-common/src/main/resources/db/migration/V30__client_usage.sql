CREATE TABLE IF NOT EXISTS character_client_usage (
    character_id INTEGER NOT NULL REFERENCES account_characters (id) ON DELETE CASCADE,
    client VARCHAR(16) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    logins INTEGER NOT NULL DEFAULT 0,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (character_id, client, platform)
);

CREATE INDEX IF NOT EXISTS idx_character_client_usage_client
    ON character_client_usage (client, platform, logins);

CREATE INDEX IF NOT EXISTS idx_character_client_usage_platform
    ON character_client_usage (platform, client, logins);
