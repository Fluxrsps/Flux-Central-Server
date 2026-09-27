SELECT enabled, buy_enabled, sell_enabled, buy_spread_bps, sell_spread_bps, sell_floor,
       hourly_cap_buy, daily_cap_buy, hourly_cap_sell, daily_cap_sell, per_account_daily_cap,
       wind_down_days, player_volume_threshold_bps, consecutive_days_above, pinned
FROM exchange_system_liquidity
WHERE obj_id = ?
