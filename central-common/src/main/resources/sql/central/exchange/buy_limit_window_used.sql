-- Params: character_id, obj_id, window_hours, now. A closed window counts for nothing.
SELECT filled
FROM exchange_buy_limit_window
WHERE character_id = ? AND obj_id = ? AND window_start + make_interval(hours => ?) > ?
