SELECT id, gross_value, net_value + tax
FROM exchange_trades
WHERE gross_value <> net_value + tax
