INSERT INTO exchange_orders (
    character_id, obj_id, side, source, quantity, limit_price, client_request_id, correlation_id,
    world, created_by, expires_at, created_at, updated_at
)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
RETURNING {columns}
