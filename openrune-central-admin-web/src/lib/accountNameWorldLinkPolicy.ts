/**
 * Mirrors `dev.or2.central.account.AccountNameAuthPolicy` + world-link precheck for use on a
 * web/launcher **before** opening a TCP world-link session to Central. Central still re-validates on login.
 */
export const ACCOUNT_NAME_MAX_CANONICAL_LENGTH = 12;
export const ACCOUNT_NAME_MAX_RAW_CHARS = 64;

export function rawWorldLinkUsernameHasIllegalCharacters(raw: string): boolean {
  for (const ch of raw) {
    if (ch.length !== 1) return true;
    const okLetterOrDigit = /^[\p{L}\p{N}]$/u.test(ch);
    if (!okLetterOrDigit && ch !== " " && ch !== "\t") return true;
  }
  return false;
}

export function canonicalizeWorldLinkAccountName(rawTrimmed: string): string {
  let out = "";
  let lastWasSpace = false;
  for (const ch of rawTrimmed) {
    if (/[a-zA-Z0-9]/.test(ch)) {
      out += ch;
      lastWasSpace = false;
    } else if (ch === " " || ch === "\t") {
      if (out.length > 0 && !lastWasSpace) {
        out += " ";
        lastWasSpace = true;
      }
    } else {
      lastWasSpace = false;
    }
  }
  return out.trim();
}

export function collisionKeyForAccountName(canonical: string): string {
  let s = "";
  for (const ch of canonical) {
    if (/[a-zA-Z0-9]/.test(ch)) s += ch.toLowerCase();
  }
  return s;
}

export function findProfanityInCanonical(
  canonical: string,
  roots: ReadonlySet<string>,
): string | null {
  if (roots.size === 0) return null;
  const lower = canonical.toLowerCase();
  const compact = lower.replace(/[^a-z0-9]+/g, "");
  for (const root of roots) {
    const trimmed = root.trim();
    if (!trimmed) continue;
    const rl = trimmed.toLowerCase();
    if (lower.includes(rl)) return trimmed;
    const rc = rl.replace(/[^a-z0-9]+/g, "");
    if (rc.length > 0 && compact.includes(rc)) return trimmed;
  }
  return null;
}

export type AccountNameFormatReject =
  | "EMPTY"
  | "INVALID_LOGIN_CHARS"
  | "TOO_LONG"
  | "CONTAINS_MOD"
  | { kind: "DECEPTIVE"; fragment: string }
  | { kind: "PROFANITY"; matched: string };

export function validateWorldLinkAccountNameCanonical(
  canonical: string,
  badWordRoots: ReadonlySet<string>,
  deceptiveFragments: ReadonlySet<string>,
): AccountNameFormatReject | null {
  if (canonical.length === 0) return "EMPTY";
  if (canonical.length > ACCOUNT_NAME_MAX_CANONICAL_LENGTH) return "TOO_LONG";
  const lower = canonical.toLowerCase();
  for (const frag of deceptiveFragments) {
    const fl = frag.trim().toLowerCase();
    if (!fl) continue;
    if (lower.includes(fl)) {
      return fl === "mod" ? "CONTAINS_MOD" : { kind: "DECEPTIVE", fragment: frag };
    }
  }
  const hit = findProfanityInCanonical(canonical, badWordRoots);
  if (hit) return { kind: "PROFANITY", matched: hit };
  return null;
}

export type WorldLinkAccountNamePrecheck =
  | { ok: true; canonical: string }
  | { ok: false; reasonKind: "BAD_FRAME" }
  | {
      ok: false;
      reasonKind: string;
      scriptLine1: string;
      scriptLine2: string;
      scriptLine3: string;
    };

function policyScriptLines(reason: AccountNameFormatReject): [string, string, string] {
  switch (reason) {
    case "EMPTY":
      return [
        "That name is not usable.",
        "Use letters, numbers, and spaces only.",
        "Try a different name.",
      ];
    case "INVALID_LOGIN_CHARS":
      return [
        "That name is not usable.",
        "Remove odd symbols or try a simpler spelling.",
        "",
      ];
    case "TOO_LONG":
      return [
        "That name is too long.",
        `Account names can be at most ${ACCOUNT_NAME_MAX_CANONICAL_LENGTH} characters after cleaning.`,
        "Shorten it and try again.",
      ];
    case "CONTAINS_MOD":
      return [
        "That name is not allowed.",
        'Names cannot contain the sequence "mod".',
        "Pick a different name.",
      ];
    default:
      if (reason.kind === "DECEPTIVE") {
        return [
          "That name is not allowed.",
          "It looks like staff or official branding.",
          "Pick a different name.",
        ];
      }
      return [
        "That name is not allowed.",
        "It contains blocked language.",
        "Pick a different name.",
      ];
  }
}

export function precheckWorldLinkAccountName(
  usernameWire: string,
  badWordRoots: ReadonlySet<string>,
  deceptiveFragments: ReadonlySet<string>,
): WorldLinkAccountNamePrecheck {
  if (!usernameWire.trim() || usernameWire.length > ACCOUNT_NAME_MAX_RAW_CHARS) {
    return { ok: false, reasonKind: "BAD_FRAME" };
  }
  const raw = usernameWire.trim();
  if (rawWorldLinkUsernameHasIllegalCharacters(raw)) {
    const [a, b, c] = policyScriptLines("INVALID_LOGIN_CHARS");
    return {
      ok: false,
      reasonKind: "INVALID_LOGIN_CHARS",
      scriptLine1: a,
      scriptLine2: b,
      scriptLine3: c,
    };
  }
  const canonical = canonicalizeWorldLinkAccountName(raw);
  const rej = validateWorldLinkAccountNameCanonical(canonical, badWordRoots, deceptiveFragments);
  if (rej) {
    const [a, b, c] = policyScriptLines(rej);
    const reasonKind =
      rej === "EMPTY"
        ? "EMPTY"
        : rej === "INVALID_LOGIN_CHARS"
          ? "INVALID_LOGIN_CHARS"
          : rej === "TOO_LONG"
            ? "TOO_LONG"
            : rej === "CONTAINS_MOD"
              ? "CONTAINS_MOD"
              : rej.kind === "DECEPTIVE"
                ? "DECEPTIVE"
                : "PROFANITY";
    return { ok: false, reasonKind, scriptLine1: a, scriptLine2: b, scriptLine3: c };
  }
  return { ok: true, canonical };
}

export type AccountNameBadWordsJson = {
  phrases: string[];
  count: number;
  maxCanonicalLength: number;
};

export async function fetchAccountNameBadWordsFromCentral(
  centralHttpOrigin: string,
): Promise<ReadonlySet<string>> {
  const u = new URL("/admin/api/account-name-bad-words.json", centralHttpOrigin);
  const res = await fetch(u.toString(), { cache: "no-store" });
  if (!res.ok) throw new Error(`bad words HTTP ${res.status}`);
  const body = (await res.json()) as AccountNameBadWordsJson;
  return new Set(body.phrases ?? []);
}

export type AccountNameDeceptiveFragmentsJson = {
  fragments: string[];
  count: number;
};

export async function fetchAccountNameDeceptiveFragmentsFromCentral(
  centralHttpOrigin: string,
): Promise<ReadonlySet<string>> {
  const u = new URL("/admin/api/account-name-deceptive-fragments.json", centralHttpOrigin);
  const res = await fetch(u.toString(), { cache: "no-store" });
  if (!res.ok) throw new Error(`deceptive fragments HTTP ${res.status}`);
  const body = (await res.json()) as AccountNameDeceptiveFragmentsJson;
  return new Set(body.fragments ?? []);
}
