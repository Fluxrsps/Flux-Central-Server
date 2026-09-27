-- Params: character_id, obj_id, now, filled, window_hours, window_hours.
-- A fill inside the open window adds to it; one after it has closed opens a fresh window at now.
INSERT INTO exchange_buy_limit_window (character_id, obj_id, window_start, filled)
VALUES (?, ?, ?, ?)
ON CONFLICT (character_id, obj_id) DO UPDATE SET
    window_start = CASE WHEN exchange_buy_limit_window.window_start + make_interval(hours => ?) <= EXCLUDED.window_start
                        THEN EXCLUDED.window_start ELSE exchange_buy_limit_window.window_start END,
    filled = CASE WHEN exchange_buy_limit_window.window_start + make_interval(hours => ?) <= EXCLUDED.window_start
                  THEN EXCLUDED.filled ELSE exchange_buy_limit_window.filled + EXCLUDED.filled END
