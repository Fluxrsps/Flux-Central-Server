INSERT INTO exchange_account_state (character_id, invalid_input_count, rate_limit_trips, last_rejected_at, updated_at)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT (character_id) DO UPDATE SET
    invalid_input_count = exchange_account_state.invalid_input_count + EXCLUDED.invalid_input_count,
    rate_limit_trips = exchange_account_state.rate_limit_trips + EXCLUDED.rate_limit_trips,
    last_rejected_at = EXCLUDED.last_rejected_at,
    updated_at = EXCLUDED.updated_at
RETURNING invalid_input_count, rate_limit_trips
