package dev.or2.central.account

import dev.or2.central.display.DisplayNameFormatResult

/**
 * Pure account-name checks for world-link login (no DB). Use on a **game / edge server** before
 * opening a world-link connection to Central; Central still runs the same rules again on login.
 */
public object AccountNameWorldLinkPrecheck {

    public fun evaluateRawUsernameForLogin(
        usernameWire: String,
        badWordRoots: Set<String>,
    ): AccountNameWorldLinkPrecheckResult {
        if (usernameWire.isBlank() || usernameWire.length > AccountNameAuthPolicy.MAX_RAW_UTF_BYTES) {
            return AccountNameWorldLinkPrecheckResult.BadFrame
        }
        val raw = usernameWire.trim()
        if (AccountNameAuthPolicy.rawWorldLinkUsernameHasIllegalCharacters(raw)) {
            return AccountNameWorldLinkPrecheckResult.Policy(DisplayNameFormatResult.InvalidLoginCharacters)
        }
        val canonical = AccountNameAuthPolicy.canonicalize(raw)
        return when (val f = AccountNameAuthPolicy.validateCanonical(canonical, badWordRoots)) {
            DisplayNameFormatResult.Ok -> AccountNameWorldLinkPrecheckResult.Ok(canonical = canonical)
            else -> AccountNameWorldLinkPrecheckResult.Policy(f)
        }
    }
}

public sealed class AccountNameWorldLinkPrecheckResult {
    public data class Ok(public val canonical: String) : AccountNameWorldLinkPrecheckResult()

    public data object BadFrame : AccountNameWorldLinkPrecheckResult()

    public data class Policy(public val reason: DisplayNameFormatResult) : AccountNameWorldLinkPrecheckResult()
}
