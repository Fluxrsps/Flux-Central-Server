SELECT account_id,
       discord_user_id,
       code,
       wrong_attempts,
       expires_at,
       created_at
FROM discord_link_pending
WHERE discord_user_id = ?
  AND expires_at > CURRENT_TIMESTAMP
