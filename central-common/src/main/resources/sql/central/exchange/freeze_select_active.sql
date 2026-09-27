SELECT id, scope, target_id, reason_code, reason, hold_orders, staff_character_id, created_at
FROM exchange_freezes
WHERE lifted_at IS NULL
ORDER BY created_at DESC
