-- One open alert per (kind, item). A repeat only refreshes last_seen_at and details; xmax = 0 tells
-- the caller whether this row is new, which is what decides if staff get pinged.
INSERT INTO exchange_alerts (kind, severity, obj_id, details, status, created_at, last_seen_at)
VALUES (?, ?, ?, ?::jsonb, 'OPEN', ?, ?)
ON CONFLICT (kind, obj_id) WHERE status = 'OPEN'
DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at, details = EXCLUDED.details, severity = EXCLUDED.severity
RETURNING id, (xmax = 0) AS inserted
