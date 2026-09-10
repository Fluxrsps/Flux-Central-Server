package dev.or2.central.account

import dev.or2.central.display.DisplayNameFormatResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccountNameAuthPolicyTest {
    @Test
    fun canonicalize_strips_punctuation_and_collapses_space() {
        assertEquals("RsPlayer", AccountNameAuthPolicy.canonicalize("Rs@Player"))
        assertEquals("A B", AccountNameAuthPolicy.canonicalize("  A   B  "))
    }

    @Test
    fun collision_key_is_alnum_lower() {
        assertEquals("ab", AccountNameAuthPolicy.collisionKey("A B"))
    }

    @Test
    fun validate_rejects_mod_substring() {
        val r = AccountNameAuthPolicy.validateCanonical("abmodc", emptySet())
        assertTrue(r is DisplayNameFormatResult.ContainsMod)
    }

    @Test
    fun validate_rejects_too_long_canonical() {
        val r = AccountNameAuthPolicy.validateCanonical("abcdefghijklm", emptySet())
        assertIs<DisplayNameFormatResult.TooLong>(r)
    }

    @Test
    fun validate_rejects_deceptive_fragment_from_file() {
        val r = AccountNameAuthPolicy.validateCanonical("MyJagexFan", emptySet())
        assertIs<DisplayNameFormatResult.Deceptive>(r)
    }

    @Test
    fun validate_profanity_from_roots() {
        val r = AccountNameAuthPolicy.validateCanonical("ab xyzzy", setOf("xyzzy"))
        assertIs<DisplayNameFormatResult.Profanity>(r)
    }

    @Test
    fun raw_world_link_illegal_character_detection() {
        assertTrue(AccountNameAuthPolicy.rawWorldLinkUsernameHasIllegalCharacters("a@b"))
        assertTrue(AccountNameAuthPolicy.rawWorldLinkUsernameHasIllegalCharacters("a_b"))
        assertFalse(AccountNameAuthPolicy.rawWorldLinkUsernameHasIllegalCharacters("Valid Name"))
        assertFalse(AccountNameAuthPolicy.rawWorldLinkUsernameHasIllegalCharacters("Éclair"))
    }

    /**
     * The listing feeds /admin/api/account-name-deceptive-fragments.json, which the
     * website and admin web both consume. An empty set there silently disables
     * deceptive-name blocking on every caller.
     */
    @Test
    fun deceptive_fragments_listing_is_loaded_from_the_classpath() {
        val fragments = AccountNameAuthPolicy.deceptiveFragmentsForListing()

        assertTrue(fragments.isNotEmpty())
        assertTrue(fragments.any { it.equals("mod", ignoreCase = true) })
        assertTrue(fragments.any { it.equals("jagex", ignoreCase = true) })
    }
}
