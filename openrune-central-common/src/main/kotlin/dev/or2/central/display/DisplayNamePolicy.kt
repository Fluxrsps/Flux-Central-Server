package dev.or2.central.display

/**
 * OSRS-style display name rules shared by Central, admin web (mirror in TS), and game servers.
 *
 * [accounts.login_username] uses the same character set and profanity / staff-deception rules as display
 * names, but allows a longer maximum ([LOGIN_USERNAME_MAX_LENGTH]); Central enforces that on world login.
 *
 * Display names are **case-sensitive** in the database; do not lowercase them when saving.
 */
public object DisplayNamePolicy {

    /** May be raised later; keep game + admin constants in sync. */
    public const val MAX_LENGTH: Int = 12

    /**
     * World-link **login username** max length (separate from in-game [MAX_LENGTH] display names, which stay 12).
     * Must match the frame parser cap in Central world-link login.
     */
    public const val LOGIN_USERNAME_MAX_LENGTH: Int = 64

    /** Millis between free renames for a character (bond or staff tools may bypass). */
    public const val COOLDOWN_MILLIS: Long = 24L * 24 * 60 * 60 * 1000

    /** How long the previous name stays reserved for everyone else. */
    public const val HOLD_RELEASE_MILLIS: Long = 35L * 24 * 60 * 60 * 1000

    /**
     * After a rename, further renames in this window require a bond (staff / admin panel bypass).
     * Jagex: first 28 days of the 35-day hold window.
     */
    public const val BOND_OR_STAFF_RENAME_WINDOW_MILLIS: Long = 28L * 24 * 60 * 60 * 1000

    private val allowedCharRegex = Regex("""[a-zA-Z0-9_-]""")

    /** Lowercase fragments blocked as misleading (extend as needed). */
    private val deceptiveFragments: Set<String> =
        setOf(
            "jagex",
            "support",
            "system",
            "official",
            "customer",
            "helpdesk",
        )

    /**
     * Strips disallowed characters (e.g. `@`); keeps letters, digits, `-`, `_`.
     * Does not trim interior spaces (they are removed entirely because they are not allowed).
     */
    public fun sanitize(raw: String): String = buildString {
        for (ch in raw) {
            if (allowedCharRegex.matches(ch.toString())) {
                append(ch)
            }
        }
    }

    public fun validateFormat(
        sanitized: String,
        badWordRoots: Set<String> = emptySet(),
        maxLength: Int = MAX_LENGTH,
    ): DisplayNameFormatResult {
        if (sanitized.isEmpty()) {
            return DisplayNameFormatResult.Empty
        }
        if (sanitized.length > maxLength) {
            return DisplayNameFormatResult.TooLong(sanitized.length)
        }
        if ("mod" in sanitized.lowercase()) {
            return DisplayNameFormatResult.ContainsMod
        }
        val lower = sanitized.lowercase()
        for (frag in deceptiveFragments) {
            if (frag in lower) {
                return DisplayNameFormatResult.Deceptive(frag)
            }
        }
        findProfanity(sanitized, badWordRoots)?.let { return DisplayNameFormatResult.Profanity(it) }
        return DisplayNameFormatResult.Ok
    }

    /**
     * World-link login username: must use only [sanitize]-allowed characters (no spaces, symbols, or Unicode
     * smuggling), then the same rules as [validateFormat] on that exact string.
     */
    public fun validateLoginUsername(
        trimmedUsername: String,
        badWordRoots: Set<String> = emptySet(),
    ): DisplayNameFormatResult {
        val s = sanitize(trimmedUsername)
        if (s.isEmpty()) {
            return DisplayNameFormatResult.Empty
        }
        if (s != trimmedUsername) {
            return DisplayNameFormatResult.InvalidLoginCharacters
        }
        return validateFormat(s, badWordRoots, LOGIN_USERNAME_MAX_LENGTH)
    }

    /**
     * Returns the first blocked phrase from [roots] that matches [sanitized], or null.
     * Uses lowercase substring match, and a punctuation-stripped compact form so entries like
     * "blow job" still match "BlowJob".
     */
    public fun findProfanity(sanitized: String, roots: Set<String>): String? {
        if (roots.isEmpty()) {
            return null
        }
        val lower = sanitized.lowercase()
        val compact = lower.replace(Regex("[^a-z0-9]+"), "")
        for (root in roots) {
            val trimmed = root.trim()
            if (trimmed.isEmpty()) {
                continue
            }
            val rl = trimmed.lowercase()
            if (lower.contains(rl)) {
                return trimmed
            }
            val rc = rl.replace(Regex("[^a-z0-9]+"), "")
            if (rc.isNotEmpty() && compact.contains(rc)) {
                return trimmed
            }
        }
        return null
    }

    /**
     * @param currentDisplayName stored display name (nullable for edge cases)
     * @param displayNameChangedAt epoch millis of last change; null if never
     * @param bannedUntilMillis epoch millis from [account_characters.banned_until], or null
     */
    public fun validatePlayerChange(
        rawInput: String,
        currentDisplayName: String?,
        displayNameChangedAt: Long?,
        bannedUntilMillis: Long?,
        nowMillis: Long,
        bondBypassesCooldownAndWindow: Boolean,
        newNameHeldUntilMillis: Long?,
        badWordRoots: Set<String> = emptySet(),
    ): DisplayNamePlayerChangeResult {
        val sanitized = sanitize(rawInput)
        when (val f = validateFormat(sanitized, badWordRoots)) {
            is DisplayNameFormatResult.Ok -> Unit
            DisplayNameFormatResult.Empty -> return DisplayNamePlayerChangeResult.InvalidFormat(f)
            is DisplayNameFormatResult.TooLong -> return DisplayNamePlayerChangeResult.InvalidFormat(f)
            DisplayNameFormatResult.ContainsMod -> return DisplayNamePlayerChangeResult.InvalidFormat(f)
            is DisplayNameFormatResult.Deceptive -> return DisplayNamePlayerChangeResult.InvalidFormat(f)
            is DisplayNameFormatResult.Profanity -> return DisplayNamePlayerChangeResult.InvalidFormat(f)
            DisplayNameFormatResult.InvalidLoginCharacters ->
                return DisplayNamePlayerChangeResult.InvalidFormat(f)
        }
        val current = currentDisplayName.orEmpty()
        if (sanitized == current) {
            return DisplayNamePlayerChangeResult.NoChange
        }
        if (bannedUntilMillis != null && bannedUntilMillis > nowMillis) {
            return DisplayNamePlayerChangeResult.Banned
        }
        if (!bondBypassesCooldownAndWindow && displayNameChangedAt != null) {
            val elapsed = nowMillis - displayNameChangedAt
            if (elapsed < COOLDOWN_MILLIS) {
                return DisplayNamePlayerChangeResult.CooldownActive
            }
            if (elapsed < BOND_OR_STAFF_RENAME_WINDOW_MILLIS) {
                return DisplayNamePlayerChangeResult.BondRequiredForRenameWindow
            }
        }
        if (newNameHeldUntilMillis != null && newNameHeldUntilMillis > nowMillis) {
            return DisplayNamePlayerChangeResult.NameHeld
        }
        return DisplayNamePlayerChangeResult.Accepted(sanitized)
    }

    /** Admin / staff panel: format + uniqueness checks only; bypass ban, cooldown, holds, bond window. */
    public fun validateStaffPanelFormat(
        rawInput: String,
        badWordRoots: Set<String> = emptySet(),
    ): DisplayNameStaffResult {
        val sanitized = sanitize(rawInput)
        return when (val f = validateFormat(sanitized, badWordRoots)) {
            is DisplayNameFormatResult.Ok -> DisplayNameStaffResult.Accepted(sanitized)
            else -> DisplayNameStaffResult.InvalidFormat(f)
        }
    }
}

public sealed class DisplayNameFormatResult {
    public data object Ok : DisplayNameFormatResult()

    public data object Empty : DisplayNameFormatResult()

    public data class TooLong(public val length: Int) : DisplayNameFormatResult()

    public data object ContainsMod : DisplayNameFormatResult()

    public data class Deceptive(public val matchedFragment: String) : DisplayNameFormatResult()

    /** Matched entry from the merged remote + local profanity list. */
    public data class Profanity(public val matched: String) : DisplayNameFormatResult()

    /**
     * Login username contained spaces, symbols, or other characters outside letters, digits, `-`, and `_`.
     * (Display-name changes may still [sanitize] input; login usernames must match the sanitized form exactly.)
     */
    public data object InvalidLoginCharacters : DisplayNameFormatResult()
}

public sealed class DisplayNamePlayerChangeResult {
    public data object NoChange : DisplayNamePlayerChangeResult()

    public data object Banned : DisplayNamePlayerChangeResult()

    public data class InvalidFormat(public val reason: DisplayNameFormatResult) : DisplayNamePlayerChangeResult()

    public data object CooldownActive : DisplayNamePlayerChangeResult()

    public data object BondRequiredForRenameWindow : DisplayNamePlayerChangeResult()

    public data object NameHeld : DisplayNamePlayerChangeResult()

    public data class Accepted(public val sanitized: String) : DisplayNamePlayerChangeResult()
}

public sealed class DisplayNameStaffResult {
    public data class Accepted(public val sanitized: String) : DisplayNameStaffResult()

    public data class InvalidFormat(public val reason: DisplayNameFormatResult) : DisplayNameStaffResult()
}
