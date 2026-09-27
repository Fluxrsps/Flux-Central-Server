SELECT obj_id, frozen, base_price, buy_limit, tax_category, tax_exempt, launch_buy_limit, launch_limit_until
FROM exchange_items
WHERE obj_id = ?
