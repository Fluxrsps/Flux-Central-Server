INSERT INTO exchange_flags (kind, trade_id, order_id, obj_id, character_id, counterparty_id, details, created_at)
VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
