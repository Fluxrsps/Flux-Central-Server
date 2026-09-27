INSERT INTO exchange_reversals (trade_id, staff_character_id, reason, dry_run, correlation_id, plan, shortfall, created_at, applied_at)
VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
RETURNING id
