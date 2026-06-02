package dev.or2.central.display

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DisplayNamePolicyTest {
    @Test
    fun sanitize_strips_at() {
        assertEquals("RsPlayer", DisplayNamePolicy.sanitize("Rs@Player"))
    }

    @Test
    fun sanitize_preserves_case() {
        assertEquals("AbC", DisplayNamePolicy.sanitize("AbC"))
    }

    @Test
    fun format_rejects_mod_substring() {
        val r = DisplayNamePolicy.validateFormat(DisplayNamePolicy.sanitize("xModx"))
        assertTrue(r is DisplayNameFormatResult.ContainsMod)
    }

    @Test
    fun format_rejects_profanity_list() {
        val r =
            DisplayNamePolicy.validateFormat(
                DisplayNamePolicy.sanitize("F_u_cked"),
                setOf("fuck"),
            )
        assertIs<DisplayNameFormatResult.Profanity>(r)
    }

    @Test
    fun format_allows_longer_names_when_max_length_raised() {
        val fifteen = "abcdefghijklmno"
        assertEquals(15, fifteen.length)
        val rDefault = DisplayNamePolicy.validateFormat(fifteen, emptySet())
        assertIs<DisplayNameFormatResult.TooLong>(rDefault)
        val rLogin = DisplayNamePolicy.validateFormat(fifteen, emptySet(), DisplayNamePolicy.LOGIN_USERNAME_MAX_LENGTH)
        assertEquals(DisplayNameFormatResult.Ok, rLogin)
    }

    @Test
    fun login_username_rejects_non_alnum_symbols() {
        assertTrue(DisplayNamePolicy.validateLoginUsername("a@b", emptySet()) is DisplayNameFormatResult.InvalidLoginCharacters)
        assertEquals(DisplayNameFormatResult.Ok, DisplayNamePolicy.validateLoginUsername("ab", emptySet()))
    }

    @Test
    fun player_change_cooldown_then_bond_window() {
        val now = 1_000_000_000_000L
        val changed = now - (25L * 24 * 60 * 60 * 1000) // 25 days ago
        val r =
            DisplayNamePolicy.validatePlayerChange(
                rawInput = "NewName",
                currentDisplayName = "Old",
                displayNameChangedAt = changed,
                bannedUntilMillis = null,
                nowMillis = now,
                bondBypassesCooldownAndWindow = false,
                newNameHeldUntilMillis = null,
            )
        assertIs<DisplayNamePlayerChangeResult.BondRequiredForRenameWindow>(r)
    }

    @Test
    fun player_change_reclaim_previous_still_blocked_while_name_held() {
        val now = 1_000_000_000_000L
        val r =
            DisplayNamePolicy.validatePlayerChange(
                rawInput = "Prev",
                currentDisplayName = "Curr",
                displayNameChangedAt = null,
                bannedUntilMillis = null,
                nowMillis = now,
                bondBypassesCooldownAndWindow = false,
                newNameHeldUntilMillis = now + 1,
            )
        assertIs<DisplayNamePlayerChangeResult.NameHeld>(r)
    }
}
