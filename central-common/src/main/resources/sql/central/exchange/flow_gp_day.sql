-- Params: day. GP created and destroyed outside the exchange in one day, from the worlds' flow
-- counters. Coins are obj 995.
SELECT
    COALESCE(sum(CASE WHEN kind = 'alchemy' THEN gp_value END), 0) AS alchemy,
    COALESCE(sum(CASE WHEN kind = 'shop_sell' THEN gp_value END), 0) AS shop_sell,
    COALESCE(sum(CASE WHEN kind IN ('npc_drop', 'admin_spawn') AND obj_id = 995 THEN gp_value END), 0) AS coin_drops,
    COALESCE(sum(CASE WHEN kind = 'shop_buy' THEN gp_value END), 0) AS shop_buy,
    COALESCE(sum(CASE WHEN kind IN ('destroy', 'ground_despawn') AND obj_id = 995 THEN gp_value END), 0) AS coins_lost,
    COALESCE(sum(CASE WHEN kind IN ('npc_drop', 'admin_spawn') AND obj_id <> 995 THEN gp_value END), 0) AS item_faucets,
    COALESCE(sum(CASE WHEN kind IN ('destroy', 'ground_despawn') AND obj_id <> 995 THEN gp_value END), 0) AS item_sinks
FROM economy_flow_daily
WHERE day = ?
