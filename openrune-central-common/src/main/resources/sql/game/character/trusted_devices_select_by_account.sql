SELECT
    device_id,
    verified_at
FROM account_trusted_devices
WHERE account_id = ?
ORDER BY device_id
