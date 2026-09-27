-- One fill folded into its hour. open stays as first written; close is always the latest fill.
INSERT INTO exchange_stats_hourly (
    obj_id, bucket_start, open_price, close_price, low_price, high_price,
    vwap_numerator, vwap_denominator, volume, transaction_count,
    player_volume, system_volume, seeded_volume, tax_collected
)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 0, ?)
ON CONFLICT (obj_id, bucket_start)
DO UPDATE SET
    close_price = EXCLUDED.close_price,
    low_price = LEAST(exchange_stats_hourly.low_price, EXCLUDED.low_price),
    high_price = GREATEST(exchange_stats_hourly.high_price, EXCLUDED.high_price),
    vwap_numerator = exchange_stats_hourly.vwap_numerator + EXCLUDED.vwap_numerator,
    vwap_denominator = exchange_stats_hourly.vwap_denominator + EXCLUDED.vwap_denominator,
    volume = exchange_stats_hourly.volume + EXCLUDED.volume,
    transaction_count = exchange_stats_hourly.transaction_count + 1,
    player_volume = exchange_stats_hourly.player_volume + EXCLUDED.player_volume,
    system_volume = exchange_stats_hourly.system_volume + EXCLUDED.system_volume,
    tax_collected = exchange_stats_hourly.tax_collected + EXCLUDED.tax_collected,
    updated_at = CURRENT_TIMESTAMP
