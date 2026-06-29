CREATE TABLE IF NOT EXISTS character_friends (
                                                 owner_character_id INTEGER NOT NULL REFERENCES account_characters (id) ON DELETE CASCADE,
    friend_character_id INTEGER NOT NULL REFERENCES account_characters (id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_character_id, friend_character_id),
    CONSTRAINT chk_character_friends_not_self CHECK (owner_character_id <> friend_character_id)
    );

CREATE INDEX IF NOT EXISTS idx_character_friends_friend
    ON character_friends (friend_character_id);

CREATE TABLE IF NOT EXISTS character_ignores (
                                                 owner_character_id INTEGER NOT NULL REFERENCES account_characters (id) ON DELETE CASCADE,
    ignored_character_id INTEGER NOT NULL REFERENCES account_characters (id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_character_id, ignored_character_id),
    CONSTRAINT chk_character_ignores_not_self CHECK (owner_character_id <> ignored_character_id)
    );

CREATE INDEX IF NOT EXISTS idx_character_ignores_ignored
    ON character_ignores (ignored_character_id);

CREATE TABLE IF NOT EXISTS character_chat_filters (
                                                      character_id INTEGER PRIMARY KEY REFERENCES account_characters (id) ON DELETE CASCADE,
    public_chat INTEGER NOT NULL DEFAULT 0,
    private_chat INTEGER NOT NULL DEFAULT 0,
    trade_chat INTEGER NOT NULL DEFAULT 0
    );