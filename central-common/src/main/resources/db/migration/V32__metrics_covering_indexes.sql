-- Widens the indexes the metrics pages read into covering indexes.
--
-- Both tables were already indexed on the columns the questions filter by, so the range
-- scans were already fast. What they still did was visit the heap once per row to read
-- the columns they actually wanted, and on an append-only table the rows in a window are
-- spread across as many pages as the window is wide. Carrying those columns as INCLUDE
-- payload lets the same scans finish without the heap.
--
-- Each original is dropped rather than kept: an index with the same leading key columns
-- serves every plan the old one did, so keeping both would only pay the write cost twice.

CREATE INDEX IF NOT EXISTS idx_central_metric_events_metric_time_covering
    ON central_metric_events (metric, occurred_at) INCLUDE (subject, character_id);

DROP INDEX IF EXISTS idx_central_metric_events_metric_time;

-- Online counts across all worlds. `world_id` is included because the counts are summed
-- per sampled instant before they are bucketed.
CREATE INDEX IF NOT EXISTS idx_online_samples_time_covering
    ON online_samples (sampled_at) INCLUDE (world_id, online_count);

DROP INDEX IF EXISTS idx_online_samples_time;

-- The same narrowed to one world. Ascending rather than descending: a btree is walked in
-- either direction, so the order costs nothing and the two indexes now read the same way.
CREATE INDEX IF NOT EXISTS idx_online_samples_world_time_covering
    ON online_samples (world_id, sampled_at) INCLUDE (online_count);

DROP INDEX IF EXISTS idx_online_samples_world_time;
