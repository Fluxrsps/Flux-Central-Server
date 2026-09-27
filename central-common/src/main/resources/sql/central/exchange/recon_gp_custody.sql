-- Globally: every GP the ledger says went to a collection box is either still there or was
-- claimed (a released claim went back into the box, so it is not counted twice).
SELECT
    (SELECT COALESCE(sum(amount), 0) FROM exchange_events
     WHERE event_type IN ('RESERVATION_RELEASED', 'GP_CREDITED') AND amount IS NOT NULL) AS credited,
    (SELECT COALESCE(sum(amount), 0) FROM exchange_collection_gp)
    + (SELECT COALESCE(sum(gp), 0) FROM exchange_claims WHERE status IN ('PENDING', 'COMPLETE')) AS held
