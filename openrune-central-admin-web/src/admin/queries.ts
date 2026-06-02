/** Worlds + realms join (same shape as former admin API list). */
export const WORLDS_ADMIN_LIST = `
SELECT
  w.world_id,
  w.flags,
  w.host,
  w.activity,
  w.location,
  w.sort_order,
  w.enabled,
  w.max_players,
  w.login_restrictions_enabled,
  w.login_min_total_level,
  w.login_min_rights_token,
  w.login_gate_min_level_enabled,
  w.login_gate_rights_enabled,
  w.login_gate_whitelist_enabled,
  CASE
    WHEN w.world_key_sha256 IS NOT NULL AND length(w.world_key_sha256) > 0 THEN 1
    ELSE 0
  END AS has_key,
  COALESCE(sc.cnt, 0) AS online_count,
  w.realm_id,
  r.name AS realm_name,
  r.login_message,
  r.login_broadcast,
  r.spawn_coord,
  r.respawn_coord,
  r.dev_mode,
  r.require_registration,
  r.auto_assign_display_names,
  r.player_xp_rate_in_hundreds,
  r.global_xp_rate_in_hundreds
FROM worlds w
INNER JOIN realms r ON r.realm_id = w.realm_id
LEFT JOIN (
  SELECT world_id, COUNT(*) AS cnt
  FROM sessions
  GROUP BY world_id
) sc ON sc.world_id = w.world_id
ORDER BY w.sort_order ASC, w.world_id ASC
`.trim();

export const WORLD_WHITELIST_LIST = `
SELECT login_username
FROM world_login_whitelist
WHERE world_id = $1
ORDER BY id ASC
`.trim();

export const WORLD_WHITELIST_DELETE = `
DELETE FROM world_login_whitelist
WHERE world_id = $1
`.trim();

export const WORLD_WHITELIST_INSERT = `
INSERT INTO world_login_whitelist (world_id, login_username)
VALUES ($1, $2)
`.trim();

export const REALMS_LIST = `SELECT * FROM realms ORDER BY realm_id ASC`;

export function escapeLike(s: string): string {
  return s.replaceAll("\\", "\\\\").replaceAll("%", "\\%").replaceAll("_", "\\_");
}

export const ACCOUNTS_LIST_PAGE = `
SELECT id, login_username AS username, rights, created_at, updated_at
FROM accounts
ORDER BY id DESC
LIMIT $1 OFFSET $2
`.trim();

export const ACCOUNTS_SEARCH_PAGE = `
SELECT id, login_username AS username, rights, created_at, updated_at
FROM accounts
WHERE login_username LIKE $1 ESCAPE '\\'
ORDER BY id DESC
LIMIT $2 OFFSET $3
`.trim();

export const ACCOUNTS_COUNT_ALL = `SELECT COUNT(*)::bigint AS c FROM accounts`;

export const ACCOUNTS_COUNT_SEARCH = `
SELECT COUNT(*)::bigint AS c FROM accounts WHERE login_username LIKE $1 ESCAPE '\\'
`.trim();

export const ACCOUNT_BY_ID = `
SELECT id, login_username AS username, rights, created_at, updated_at
FROM accounts
WHERE id = $1
`.trim();

export const ACCOUNT_SESSIONS = `
SELECT id, account_id, world_id, created_at, last_seen_at
FROM sessions
WHERE account_id = $1
ORDER BY last_seen_at DESC NULLS LAST, id DESC
LIMIT 100
`.trim();

export const ACCOUNT_CHARACTERS = `
SELECT id, realm_id, display_name, level, world_id,
       last_login, online_central_world_id
FROM account_characters
WHERE account_id = $1
ORDER BY realm_id ASC, id ASC
`.trim();

export const PUNISHMENTS_FOR_ACCOUNT = `
SELECT
  p.id, p.scope, p.kind, p.issued_at, p.reason, p.issued_by, p.status
FROM punishments p
WHERE (p.scope = 'account' AND p.account_id = $1)
   OR (
     p.scope = 'character'
     AND p.character_id IN (SELECT c.id FROM account_characters c WHERE c.account_id = $2)
   )
ORDER BY p.issued_at DESC
`.trim();

export const PUNISHMENT_INSERT = `
INSERT INTO punishments (
  scope, account_id, character_id, kind, issued_at, expires_at,
  reason, private_notes, public_notes, issued_by, approved_by, status, repo_link_uuid
) VALUES (
  $1, $2, $3, $4, CURRENT_TIMESTAMP, $5::timestamp, $6, NULLIF($7, ''), NULLIF($8, ''), $9, NULLIF($10, ''), 'active', NULL::uuid
)
`.trim();

export const PUNISHMENT_UPDATE_STATUS = `
UPDATE punishments SET status = $1 WHERE id = $2
`.trim();

export const WORLD_REBOOT_LIST_ACTIVE = `
SELECT id, world_id, reboot_at, message, status, created_at
FROM world_reboot_schedules
WHERE status = 'active' AND reboot_at > NOW()
ORDER BY reboot_at ASC
`.trim();

export const WORLD_REBOOT_INSERT = `
INSERT INTO world_reboot_schedules (world_id, reboot_at, message, created_by)
VALUES ($1::integer, $2::timestamptz, $3, $4)
RETURNING id
`.trim();

export const WORLD_REBOOT_CANCEL = `
UPDATE world_reboot_schedules
SET status = 'cancelled', cancelled_at = CURRENT_TIMESTAMP
WHERE id = $1 AND status = 'active'
`.trim();

export const WORLD_BROADCAST_INSERT = `
INSERT INTO world_broadcast_log (world_id, message, url, icon, created_by)
VALUES ($1::integer, $2, $3, $4, $5)
RETURNING id
`.trim();
