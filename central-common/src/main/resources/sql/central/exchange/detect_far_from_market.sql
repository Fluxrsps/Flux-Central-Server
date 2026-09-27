-- Player trades settled far from the market price. Value transfer through the exchange looks
-- exactly like this: two accounts, one absurd price.
SELECT t.id, t.buyer_character_id, t.seller_character_id, t.obj_id, t.unit_price, t.quantity, p.price
FROM exchange_trades t
JOIN exchange_market_prices p ON p.obj_id = t.obj_id
WHERE t.executed_at > ?
  AND t.source = 'PLAYER'
  AND abs(t.unit_price - p.price) * 10000 > p.price * ?
  AND NOT EXISTS (
      SELECT 1 FROM exchange_flags f WHERE f.trade_id = t.id AND f.kind = 'PRICE_FAR_FROM_MARKET'
  )
ORDER BY t.id
