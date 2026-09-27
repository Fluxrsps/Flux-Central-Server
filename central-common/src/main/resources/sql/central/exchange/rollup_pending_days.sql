-- (item, day) pairs whose hourly buckets changed since the daily row was last built, or that have
-- no daily row yet. Today is included so the current day is always fresh.
--
-- The hours are aggregated in a subquery before the join: grouping and outer-joining in one
-- level would leave bucket_start referenced outside both the GROUP BY and an aggregate.
SELECT x.obj_id, x.day
FROM (
    SELECT h.obj_id AS obj_id,
           (h.bucket_start AT TIME ZONE 'UTC')::date AS day,
           max(h.updated_at) AS last_hour_update
    FROM exchange_stats_hourly h
    GROUP BY h.obj_id, (h.bucket_start AT TIME ZONE 'UTC')::date
) x
LEFT JOIN exchange_stats_daily d ON d.obj_id = x.obj_id AND d.day = x.day
WHERE d.rolled_up_at IS NULL OR x.last_hour_update > d.rolled_up_at
ORDER BY x.day, x.obj_id
LIMIT ?
