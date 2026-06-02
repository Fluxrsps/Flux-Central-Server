/**
 * Keep in sync with `DisplayNamePolicy.kt` in `:openrune-central-common`
 * (`dev.or2.central.display.DisplayNamePolicy`). Central also applies the same profanity / deception rules
 * to world login usernames (longer max length than display names).
 */
export const DISPLAY_NAME_MAX_LENGTH = 12;
/** Same as `DisplayNamePolicy.LOGIN_USERNAME_MAX_LENGTH` (Central world login). */
export const LOGIN_USERNAME_MAX_LENGTH = 64;
export const DISPLAY_NAME_COOLDOWN_MS = 24 * 24 * 60 * 60 * 1000;
export const DISPLAY_NAME_HOLD_RELEASE_MS = 35 * 24 * 60 * 60 * 1000;
export const DISPLAY_NAME_BOND_WINDOW_MS = 28 * 24 * 60 * 60 * 1000;

const ALLOWED = /^[a-zA-Z0-9_-]$/;
const DECEPTIVE = ["jagex", "support", "system", "official", "customer", "helpdesk"] as const;
const NON_ALNUM = /[^a-z0-9]+/g;

/**
 * Mirrors `DisplayNamePolicy.findProfanity` in `:openrune-central-common` for the same `phrases`
 * Central uses when optional `badWordPhrases` is supplied.
 */
export function findProfanityInSanitized(sanitized: string, roots: ReadonlySet<string>): string | null {
  if (roots.size === 0) {
    return null;
  }
  const lower = sanitized.toLowerCase();
  const compact = lower.replace(NON_ALNUM, "");
  for (const root of roots) {
    const trimmed = root.trim();
    if (!trimmed) {
      continue;
    }
    const rl = trimmed.toLowerCase();
    if (lower.includes(rl)) {
      return trimmed;
    }
    const rc = rl.replace(NON_ALNUM, "");
    if (rc.length > 0 && compact.includes(rc)) {
      return trimmed;
    }
  }
  return null;
}

export function sanitizeDisplayName(raw: string): string {
  let out = "";
  for (const ch of raw) {
    if (ALLOWED.test(ch)) {
      out += ch;
    }
  }
  return out;
}

export type StaffFormatResult =
  | { ok: true; sanitized: string }
  | { ok: false; message: string };

function validateFormatOnSanitizedDisplayName(
  sanitized: string,
  badWordPhrases?: ReadonlySet<string> | null,
): StaffFormatResult {
  if (sanitized.length === 0) {
    return { ok: false, message: "Display name is empty after removing disallowed characters." };
  }
  if (sanitized.length > DISPLAY_NAME_MAX_LENGTH) {
    return { ok: false, message: `Display name may not exceed ${DISPLAY_NAME_MAX_LENGTH} characters.` };
  }
  if (sanitized.toLowerCase().includes("mod")) {
    return { ok: false, message: 'Display name may not contain "mod".' };
  }
  const lower = sanitized.toLowerCase();
  for (const frag of DECEPTIVE) {
    if (lower.includes(frag)) {
      return { ok: false, message: `Display name may not resemble staff or official channels (${frag}).` };
    }
  }
  if (badWordPhrases && badWordPhrases.size > 0) {
    const hit = findProfanityInSanitized(sanitized, badWordPhrases);
    if (hit) {
      return { ok: false, message: `This name contains a disallowed word or phrase (${hit}).` };
    }
  }
  return { ok: true, sanitized };
}

export type PlayerRenamePolicyResult = StaffFormatResult;

/**
 * Mirrors `DisplayNamePolicy.validatePlayerChange` (player / in-game rules, not staff panel).
 * Punctuation such as `@` is stripped via {@link sanitizeDisplayName} before checks (see OSRS wiki).
 */
export function validatePlayerDisplayNameChange(
  rawInput: string,
  currentDisplayName: string | null | undefined,
  displayNameChangedAt: number | null | undefined,
  bannedUntilEpochMs: number | null | undefined,
  nowMillis: number,
  bondBypassesCooldownAndWindow: boolean,
  newNameHeldUntilMillis: number | null | undefined,
  badWordPhrases?: ReadonlySet<string> | null,
): PlayerRenamePolicyResult {
  const format = validateFormatOnSanitizedDisplayName(sanitizeDisplayName(rawInput), badWordPhrases);
  if (!format.ok) {
    return format;
  }
  const sanitized = format.sanitized;
  const current = currentDisplayName ?? "";
  if (sanitized === current) {
    return { ok: false, message: "That is already your display name." };
  }
  if (bannedUntilEpochMs != null && bannedUntilEpochMs > nowMillis) {
    return { ok: false, message: "You may not change your name while banned." };
  }
  if (!bondBypassesCooldownAndWindow && displayNameChangedAt != null) {
    const elapsed = nowMillis - displayNameChangedAt;
    if (elapsed < DISPLAY_NAME_COOLDOWN_MS) {
      return { ok: false, message: "You may not change your display name yet (24-day cooldown)." };
    }
    if (elapsed < DISPLAY_NAME_BOND_WINDOW_MS) {
      return { ok: false, message: "A bond is required to change your name again during this period." };
    }
  }
  if (newNameHeldUntilMillis != null && newNameHeldUntilMillis > nowMillis) {
    return { ok: false, message: "This name is still in use." };
  }
  return { ok: true, sanitized };
}

/**
 * Staff / admin panel: mirrors `DisplayNamePolicy.validateStaffPanelFormat` — sanitizes then applies format rules.
 * DB uniqueness checks are still done separately in `AccountsTab`.
 */
export function validateStaffDisplayNameFormat(
  raw: string,
  badWordPhrases?: ReadonlySet<string> | null,
): StaffFormatResult {
  return validateFormatOnSanitizedDisplayName(sanitizeDisplayName(raw), badWordPhrases);
}

/**
 * Central login usernames and world whitelist entries: same charset as display names, max length 64,
 * optional profanity list (mirrors `DisplayNamePolicy.validateLoginUsername`).
 */
export function validateCentralLoginUsername(
  raw: string,
  badWordPhrases?: ReadonlySet<string> | null,
): StaffFormatResult {
  const trimmed = raw.trim();
  const sanitized = sanitizeDisplayName(trimmed);
  if (sanitized.length === 0) {
    return { ok: false, message: "Username is empty or uses only disallowed characters." };
  }
  if (sanitized !== trimmed) {
    return {
      ok: false,
      message:
        "Username may only contain letters, digits, hyphen (-), and underscore (_). No spaces or symbols (matches Central world login).",
    };
  }
  if (sanitized.length > LOGIN_USERNAME_MAX_LENGTH) {
    return { ok: false, message: `Username may not exceed ${LOGIN_USERNAME_MAX_LENGTH} characters.` };
  }
  if (sanitized.toLowerCase().includes("mod")) {
    return { ok: false, message: 'Username may not contain "mod".' };
  }
  const lower = sanitized.toLowerCase();
  for (const frag of DECEPTIVE) {
    if (lower.includes(frag)) {
      return { ok: false, message: `Username may not resemble staff or official channels (${frag}).` };
    }
  }
  if (badWordPhrases && badWordPhrases.size > 0) {
    const hit = findProfanityInSanitized(sanitized, badWordPhrases);
    if (hit) {
      return { ok: false, message: `Username contains a disallowed word or phrase (${hit}).` };
    }
  }
  return { ok: true, sanitized };
}
