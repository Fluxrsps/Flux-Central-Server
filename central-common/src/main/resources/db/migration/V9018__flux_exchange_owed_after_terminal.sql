-- A terminal order rejects every update, which is what keeps a finished order finished. The
-- collection tally added in V9017 lives on the order row and has to carry on moving after that:
-- the fill that completes an order credits it in the same transaction that marks it FILLED, and a
-- cancelled order is owed its refund for as long as it goes uncollected.
--
-- So the guard gains one hole, and only one. An update that changes nothing but owed_items and
-- owed_gp is allowed whatever the status; everything else about a terminal row stays frozen, and
-- no other column can ride along with the pair because the rest of the row is compared as a whole.
CREATE OR REPLACE FUNCTION exchange_orders_guard_transition_fn() RETURNS trigger AS $$
BEGIN
    IF to_jsonb(NEW) - 'owed_items' - 'owed_gp' - 'updated_at'
        = to_jsonb(OLD) - 'owed_items' - 'owed_gp' - 'updated_at' THEN
        IF NEW.owed_items < 0 OR NEW.owed_gp < 0 THEN
            RAISE EXCEPTION 'exchange order % cannot be owed a negative amount (% items, % gp)',
                OLD.id, NEW.owed_items, NEW.owed_gp USING ERRCODE = 'check_violation';
        END IF;
        NEW.updated_at := CURRENT_TIMESTAMP;
        RETURN NEW;
    END IF;
    IF OLD.status IN ('FILLED', 'CANCELLED', 'EXPIRED') THEN
        RAISE EXCEPTION 'exchange order % is terminal (%), no further updates allowed',
            OLD.id, OLD.status USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.id <> OLD.id OR NEW.character_id <> OLD.character_id OR NEW.obj_id <> OLD.obj_id
        OR NEW.side <> OLD.side OR NEW.source <> OLD.source OR NEW.quantity <> OLD.quantity
        OR NEW.limit_price <> OLD.limit_price OR NEW.client_request_id <> OLD.client_request_id
        OR NEW.correlation_id <> OLD.correlation_id OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'exchange order % identity columns are immutable', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.filled_quantity < OLD.filled_quantity THEN
        RAISE EXCEPTION 'exchange order % filled_quantity may not decrease (% -> %)',
            OLD.id, OLD.filled_quantity, NEW.filled_quantity USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status <> OLD.status THEN
        IF NOT (
            (OLD.status = 'PENDING' AND NEW.status IN ('OPEN', 'CANCELLED'))
            OR (OLD.status = 'OPEN' AND NEW.status IN ('PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'EXPIRED'))
            OR (OLD.status = 'PARTIALLY_FILLED' AND NEW.status IN ('FILLED', 'CANCELLED', 'EXPIRED'))
        ) THEN
            RAISE EXCEPTION 'exchange order % illegal transition % -> %',
                OLD.id, OLD.status, NEW.status USING ERRCODE = 'check_violation';
        END IF;
        IF OLD.status = 'PENDING' AND NEW.status = 'CANCELLED' AND NEW.cancel_reason <> 'SYSTEM_FAILURE' THEN
            RAISE EXCEPTION 'exchange order % pending orders may only be cancelled for SYSTEM_FAILURE', OLD.id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
