INSERT INTO exchange_events (
    event_type, character_id, order_id, trade_id, claim_id, obj_id, quantity, amount,
    correlation_id, staff_character_id, reason, world, metadata
)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
