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
SELECT account_name
FROM world_login_whitelist
WHERE world_id = $1
ORDER BY id ASC
`.trim();

export const WORLD_WHITELIST_DELETE = `
DELETE FROM world_login_whitelist
WHERE world_id = $1
`.trim();

export const WORLD_EXISTS = `
SELECT 1 AS exists
FROM worlds
WHERE world_id = $1
LIMIT 1
`.trim();

export const WORLD_SESSION_COUNT = `
SELECT COUNT(*)::int AS c
FROM sessions
WHERE world_id = $1
`.trim();

export const WORLD_CHARACTER_COUNT = `
SELECT COUNT(*)::int AS c
FROM account_characters
WHERE world_id = $1 OR online_central_world_id = $1
`.trim();

export const WORLD_DELETE_PREP_SESSIONS = `
DELETE FROM sessions
WHERE world_id = $1
`.trim();

export const WORLD_DELETE_PREP_CHARACTERS = `
UPDATE account_characters
SET world_id = NULL, online_central_world_id = NULL
WHERE world_id = $1 OR online_central_world_id = $1
`.trim();

export const WORLD_DELETE = `
DELETE FROM worlds
WHERE world_id = $1
`.trim();

/** Copy worlds row to [newId], repoint FKs, remove [oldId]. */
export const WORLD_RENAME_COPY = `
INSERT INTO worlds (
  world_id, flags, host, activity, location, population, sort_order, enabled, max_players, world_key_sha256, realm_id,
  login_restrictions_enabled, login_min_total_level, login_min_rights_token,
  login_gate_min_level_enabled, login_gate_rights_enabled, login_gate_whitelist_enabled
)
SELECT
  $1, flags, host, activity, location, population, sort_order, enabled, max_players, world_key_sha256, realm_id,
  login_restrictions_enabled, login_min_total_level, login_min_rights_token,
  login_gate_min_level_enabled, login_gate_rights_enabled, login_gate_whitelist_enabled
FROM worlds
WHERE world_id = $2
`.trim();

export const WORLD_RENAME_SESSIONS = `
UPDATE sessions
SET world_id = $1
WHERE world_id = $2
`.trim();

export const WORLD_RENAME_CHARACTERS_WORLD = `
UPDATE account_characters
SET world_id = $1
WHERE world_id = $2
`.trim();

export const WORLD_RENAME_CHARACTERS_ONLINE = `
UPDATE account_characters
SET online_central_world_id = $1
WHERE online_central_world_id = $2
`.trim();

export const WORLD_RENAME_ONLINE_SAMPLES = `
UPDATE online_samples
SET world_id = $1
WHERE world_id = $2
`.trim();

export const WORLD_RENAME_WHITELIST = `
UPDATE world_login_whitelist
SET world_id = $1
WHERE world_id = $2
`.trim();

export const WORLD_RENAME_REBOOT_SCHEDULES = `
UPDATE world_reboot_schedules
SET world_id = $1
WHERE world_id = $2
`.trim();

export const WORLD_RENAME_BROADCAST_LOG = `
UPDATE world_broadcast_log
SET world_id = $1
WHERE world_id = $2
`.trim();

export const WORLD_RENAME_ACTIVITY_LOGS = `
UPDATE activity_logs
SET world_id = $1
WHERE world_id = $2
`.trim();

export const WORLD_WHITELIST_INSERT = `
INSERT INTO world_login_whitelist (world_id, account_name)
VALUES ($1, $2)
`.trim();

export const REALMS_LIST = `SELECT * FROM realms ORDER BY realm_id ASC`;

export const REALM_EXISTS = `
SELECT 1 AS exists
FROM realms
WHERE realm_id = $1
LIMIT 1
`.trim();

export const REALM_WORLD_COUNT = `
SELECT COUNT(*)::int AS c
FROM worlds
WHERE realm_id = $1
`.trim();

export const REALM_DELETE = `
DELETE FROM realms
WHERE realm_id = $1
`.trim();

export const REALM_INSERT = `
INSERT INTO realms (
  realm_id, name, description, login_message, login_broadcast, spawn_coord, respawn_coord,
  dev_mode, require_registration, auto_assign_display_names,
  player_xp_rate_in_hundreds, global_xp_rate_in_hundreds
)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
`.trim();

export const REALM_UPDATE = `
UPDATE realms
SET
  name = $1,
  description = $2,
  login_message = $3,
  login_broadcast = $4,
  spawn_coord = $5,
  respawn_coord = $6,
  dev_mode = $7,
  require_registration = $8,
  auto_assign_display_names = $9,
  player_xp_rate_in_hundreds = $10,
  global_xp_rate_in_hundreds = $11
WHERE realm_id = $12
`.trim();

export const REALM_RENAME_COPY = `
INSERT INTO realms (
  realm_id, name, description, login_message, login_broadcast, spawn_coord, respawn_coord,
  dev_mode, require_registration, auto_assign_display_names,
  player_xp_rate_in_hundreds, global_xp_rate_in_hundreds
)
SELECT
  $1, name, description, login_message, login_broadcast, spawn_coord, respawn_coord,
  dev_mode, require_registration, auto_assign_display_names,
  player_xp_rate_in_hundreds, global_xp_rate_in_hundreds
FROM realms
WHERE realm_id = $2
`.trim();

export const REALM_RENAME_WORLDS = `
UPDATE worlds
SET realm_id = $1
WHERE realm_id = $2
`.trim();

export function escapeLike(s: string): string {
  return s.replaceAll("\\", "\\\\").replaceAll("%", "\\%").replaceAll("_", "\\_");
}

export const ACCOUNTS_LIST_PAGE = `
SELECT id, account_name AS username, rights, created_at, updated_at
FROM accounts
ORDER BY id DESC
LIMIT $1 OFFSET $2
`.trim();

export const ACCOUNTS_SEARCH_PAGE = `
SELECT id, account_name AS username, rights, created_at, updated_at
FROM accounts
WHERE account_name LIKE $1 ESCAPE '\\'
ORDER BY id DESC
LIMIT $2 OFFSET $3
`.trim();

export const ACCOUNTS_COUNT_ALL = `SELECT COUNT(*)::bigint AS c FROM accounts`;

export const ACCOUNTS_COUNT_SEARCH = `
SELECT COUNT(*)::bigint AS c FROM accounts WHERE account_name LIKE $1 ESCAPE '\\'
`.trim();

export const ACCOUNT_BY_ID = `
SELECT id, account_name AS username, rights, created_at, updated_at
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
SELECT id, display_name, level, world_id,
       last_login, online_central_world_id
FROM account_characters
WHERE account_id = $1
ORDER BY id ASC
`.trim();

/** One row for display-name change policy (columns may be null on older DBs). */
export const CHARACTER_DISPLAY_NAME_CONTEXT = `
SELECT
  c.id,
  c.display_name,
  c.previous_display_name,
  c.display_name_changed_at,
  CASE
    WHEN c.banned_until IS NOT NULL AND c.banned_until > CURRENT_TIMESTAMP
      THEN (EXTRACT(EPOCH FROM c.banned_until) * 1000)::bigint
    ELSE NULL
  END AS banned_until_epoch_ms
FROM account_characters c
WHERE c.id = $1::integer AND c.account_id = $2::integer
`.trim();

export const DISPLAY_NAME_HOLD_RELEASE_MS = `
SELECT (EXTRACT(EPOCH FROM h.release_at) * 1000)::bigint AS release_epoch_millis
FROM display_name_holds h
WHERE h.held_name = $1
  AND h.release_at > CURRENT_TIMESTAMP
LIMIT 1
`.trim();

export const DISPLAY_NAME_TAKEN_BY_OTHER_CHARACTER = `
SELECT 1 AS taken
FROM account_characters c
WHERE c.display_name = $1
  AND c.id <> $2::integer
LIMIT 1
`.trim();

/** Another account already uses this string as login username (case-insensitive). */
export const DISPLAY_NAME_TAKEN_AS_OTHER_LOGIN = `
SELECT 1 AS taken
FROM accounts a
WHERE lower(trim(a.account_name)) = lower(trim($1))
  AND a.id <> $2::integer
LIMIT 1
`.trim();

/**
 * Atomically sets display_name, stamps change time, reserves old name in display_name_holds (35d).
 * $5 = should_hold_old (false only when old name equals new or staff edge case).
 */
export const CHARACTER_DISPLAY_NAME_APPLY = `
WITH prior AS (
  SELECT id, account_id, display_name AS old_dn
  FROM account_characters
  WHERE id = $1::integer AND account_id = $2::integer
  FOR UPDATE
),
upd AS (
  UPDATE account_characters ac
  SET
    previous_display_name = ac.display_name,
    display_name = $3,
    display_name_changed_at = $4::bigint
  FROM prior p
  WHERE ac.id = p.id AND ac.account_id = p.account_id
  RETURNING ac.id AS updated_id, p.old_dn AS old_dn
),
ins_hold AS (
  INSERT INTO display_name_holds (held_name, release_at, source_character_id)
  SELECT u.old_dn, CURRENT_TIMESTAMP + interval '35 days', $1::integer
  FROM upd u
  WHERE $5::boolean
    AND u.old_dn IS NOT NULL
    AND u.old_dn IS DISTINCT FROM $3
  ON CONFLICT (held_name) DO UPDATE SET
    release_at = GREATEST(display_name_holds.release_at, EXCLUDED.release_at),
    source_character_id = EXCLUDED.source_character_id
  RETURNING 1
)
SELECT (SELECT updated_id FROM upd LIMIT 1) AS updated_id,
       (SELECT old_dn FROM upd LIMIT 1) AS old_name
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
