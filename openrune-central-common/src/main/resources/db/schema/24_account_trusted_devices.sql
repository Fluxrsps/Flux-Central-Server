CREATE TABLE IF NOT EXISTS account_trusted_devices (
    account_id INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    device_id INTEGER NOT NULL,
    verified_at TIMESTAMP NOT NULL,
    PRIMARY KEY (account_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_account_trusted_devices_account_id ON account_trusted_devices (account_id);
