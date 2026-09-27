INSERT INTO exchange_claims (character_id, obj_id, count, gp, client_request_id, correlation_id, world, created_at, order_id)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
RETURNING {columns}
