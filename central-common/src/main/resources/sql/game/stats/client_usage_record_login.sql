INSERT INTO character_client_usage (character_id, client, platform, logins, last_seen_at)
VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP)
ON CONFLICT (character_id, client, platform) DO UPDATE SET
    logins = character_client_usage.logins + 1,
    last_seen_at = CURRENT_TIMESTAMP
