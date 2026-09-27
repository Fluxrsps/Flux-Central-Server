-- Params: frozen?, base_price?, buy_limit?, launch_buy_limit?, launch_limit_until?, launch_state?, obj_id.
-- Each nullable param leaves its column untouched.
UPDATE exchange_items
SET frozen = COALESCE(?, frozen),
    base_price = COALESCE(?, base_price),
    buy_limit = COALESCE(?, buy_limit),
    launch_buy_limit = COALESCE(?, launch_buy_limit),
    launch_limit_until = COALESCE(?, launch_limit_until),
    launch_state = COALESCE(?, launch_state)
WHERE obj_id = ?
