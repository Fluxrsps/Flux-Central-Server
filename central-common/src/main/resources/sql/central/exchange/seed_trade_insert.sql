-- A fabricated launch trade. No orders, no parties, no tax: the CHECK on exchange_trades enforces
-- all three, so a seeded row can never be mistaken for one that moved real assets.
INSERT INTO exchange_trades (
    maker_side, obj_id, quantity, unit_price, gross_value, tax_rate_bps, tax, net_value,
    source, flags, correlation_id, world, executed_at
)
VALUES ('SELL', ?, ?, ?, ?, 0, 0, ?, 'SEEDED', ARRAY['SEEDED'], ?, 0, ?)
