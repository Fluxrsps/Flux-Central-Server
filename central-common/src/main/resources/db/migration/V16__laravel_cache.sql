CREATE TABLE IF NOT EXISTS laravel_cache (
    key VARCHAR(255) PRIMARY KEY,
    value TEXT NOT NULL,
    expiration BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_laravel_cache_expiration ON laravel_cache (expiration);

CREATE TABLE IF NOT EXISTS laravel_cache_locks (
    key VARCHAR(255) PRIMARY KEY,
    owner VARCHAR(255) NOT NULL,
    expiration BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_laravel_cache_locks_expiration ON laravel_cache_locks (expiration);