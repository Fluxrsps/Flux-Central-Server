-- Rebuilds every hourly bucket for one item from the trades that remain.
--
-- Used after seeded trades are added or purged. Open and close cannot be added to or subtracted
-- from, so the only honest way to keep them right is to recompute the bucket from the rows that
-- are actually there.
INSERT INTO exchange_stats_hourly (
    obj_id, bucket_start, open_price, close_price, low_price, high_price,
    vwap_numerator, vwap_denominator, volume, transaction_count,
    player_volume, system_volume, seeded_volume, tax_collected, updated_at
)
SELECT
    t.obj_id,
    date_trunc('hour', t.executed_at),
    (array_agg(t.unit_price ORDER BY t.executed_at, t.id))[1],
    (array_agg(t.unit_price ORDER BY t.executed_at DESC, t.id DESC))[1],
    min(t.unit_price),
    max(t.unit_price),
    sum(t.gross_value),
    sum(t.quantity),
    sum(t.quantity),
    count(*),
    COALESCE(sum(t.quantity) FILTER (WHERE t.source = 'PLAYER'), 0),
    COALESCE(sum(t.quantity) FILTER (WHERE t.source IN ('SYSTEM', 'ADMIN')), 0),
    COALESCE(sum(t.quantity) FILTER (WHERE t.source = 'SEEDED'), 0),
    sum(t.tax),
    CURRENT_TIMESTAMP
FROM exchange_trades t
WHERE t.obj_id = ?
GROUP BY t.obj_id, date_trunc('hour', t.executed_at)
ON CONFLICT (obj_id, bucket_start) DO UPDATE SET
    open_price = EXCLUDED.open_price,
    close_price = EXCLUDED.close_price,
    low_price = EXCLUDED.low_price,
    high_price = EXCLUDED.high_price,
    vwap_numerator = EXCLUDED.vwap_numerator,
    vwap_denominator = EXCLUDED.vwap_denominator,
    volume = EXCLUDED.volume,
    transaction_count = EXCLUDED.transaction_count,
    player_volume = EXCLUDED.player_volume,
    system_volume = EXCLUDED.system_volume,
    seeded_volume = EXCLUDED.seeded_volume,
    tax_collected = EXCLUDED.tax_collected,
    updated_at = CURRENT_TIMESTAMP
