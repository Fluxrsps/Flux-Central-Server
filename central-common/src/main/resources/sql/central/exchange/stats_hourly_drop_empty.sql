-- Buckets whose trades have all been purged. Left behind they would report volume that no longer
-- has any rows to back it.
DELETE FROM exchange_stats_hourly s
WHERE s.obj_id = ?
  AND NOT EXISTS (
      SELECT 1 FROM exchange_trades t
      WHERE t.obj_id = s.obj_id AND date_trunc('hour', t.executed_at) = s.bucket_start
  )
