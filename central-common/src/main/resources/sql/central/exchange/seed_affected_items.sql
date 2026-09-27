SELECT DISTINCT obj_id
FROM exchange_trades
WHERE source = 'SEEDED' AND (? = 0 OR obj_id = ?)
