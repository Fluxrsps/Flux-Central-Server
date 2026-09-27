-- Takes one reversed fill back out of its hour. Open/close/low/high are left as they were: they
-- are display values and a full rebuild from trades is a staff tool, not a settlement path.
UPDATE exchange_stats_hourly
SET volume = GREATEST(volume - ?, 0),
    transaction_count = GREATEST(transaction_count - 1, 0),
    vwap_numerator = GREATEST(vwap_numerator - ?, 0),
    vwap_denominator = GREATEST(vwap_denominator - ?, 0),
    player_volume = CASE WHEN ? = 'PLAYER' THEN GREATEST(player_volume - ?, 0) ELSE player_volume END,
    system_volume = CASE WHEN ? <> 'PLAYER' THEN GREATEST(system_volume - ?, 0) ELSE system_volume END,
    tax_collected = GREATEST(tax_collected - ?, 0),
    updated_at = CURRENT_TIMESTAMP
WHERE obj_id = ? AND bucket_start = ?
