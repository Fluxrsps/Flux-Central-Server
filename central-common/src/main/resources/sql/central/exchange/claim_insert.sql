INSERT INTO exchange_claims (character_id, obj_id, count, gp, client_request_id, correlation_id, world, created_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)
RETURNING {columns}
