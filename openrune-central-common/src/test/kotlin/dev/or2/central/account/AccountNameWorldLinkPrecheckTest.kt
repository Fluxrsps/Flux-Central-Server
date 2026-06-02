package dev.or2.central.account

import dev.or2.central.display.DisplayNameFormatResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountNameWorldLinkPrecheckTest {
    @Test
    fun blank_is_bad_frame() {
        val r = AccountNameWorldLinkPrecheck.evaluateRawUsernameForLogin("   ", emptySet())
        assertTrue(r is AccountNameWorldLinkPrecheckResult.BadFrame)
    }

    @Test
    fun policy_then_ok_branch() {
        val mod = AccountNameWorldLinkPrecheck.evaluateRawUsernameForLogin("xmodx", emptySet())
        assertTrue(mod is AccountNameWorldLinkPrecheckResult.Policy)

        val ok = AccountNameWorldLinkPrecheck.evaluateRawUsernameForLogin("Valid Name", emptySet())
        assertTrue(ok is AccountNameWorldLinkPrecheckResult.Ok)
        assertEquals("Valid Name", (ok as AccountNameWorldLinkPrecheckResult.Ok).canonical)
    }

    @Test
    fun illegal_wire_characters_are_invalid_login_characters_not_canonicalized() {
        val at = AccountNameWorldLinkPrecheck.evaluateRawUsernameForLogin("bad@name", emptySet())
        assertTrue(at is AccountNameWorldLinkPrecheckResult.Policy)
        assertEquals(
            DisplayNameFormatResult.InvalidLoginCharacters,
            (at as AccountNameWorldLinkPrecheckResult.Policy).reason,
        )

        val under = AccountNameWorldLinkPrecheck.evaluateRawUsernameForLogin("a_b", emptySet())
        assertTrue(under is AccountNameWorldLinkPrecheckResult.Policy)
        assertEquals(
            DisplayNameFormatResult.InvalidLoginCharacters,
            (under as AccountNameWorldLinkPrecheckResult.Policy).reason,
        )
    }

    @Test
    fun unicode_letters_allowed_on_wire() {
        val r = AccountNameWorldLinkPrecheck.evaluateRawUsernameForLogin("Éclair", emptySet())
        assertTrue(r is AccountNameWorldLinkPrecheckResult.Ok)
        assertFalse(AccountNameAuthPolicy.rawWorldLinkUsernameHasIllegalCharacters("Éclair"))
    }
}
