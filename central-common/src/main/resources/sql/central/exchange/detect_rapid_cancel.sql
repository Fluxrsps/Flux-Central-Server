SELECT character_id, count(*) AS n
FROM exchange_events e
WHERE event_type = 'ORDER_CANCELLED' AND reason = 'PLAYER' AND occurred_at > ?
GROUP BY character_id
HAVING count(*) >= ?
   AND NOT EXISTS (
       SELECT 1 FROM exchange_flags f
       WHERE f.kind = 'RAPID_CANCEL_REPLACE' AND f.character_id = e.character_id AND f.created_at > ?
   )
