package dev.or2.central.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RightsTest {
    @Test
    fun fromLevel() {
        assertEquals(Rights.NONE, Rights.fromLevel(0))
        assertEquals(Rights.MODERATOR, Rights.fromLevel(2))
        assertEquals(Rights.ADMINISTRATOR, Rights.fromLevel(3))
        assertEquals(Rights.NONE, Rights.fromLevel(99))
    }

    @Test
    fun parseAndFromRightsColumn() {
        assertEquals(Rights.NONE, Rights.parse(""))
        assertEquals(Rights.MODERATOR, Rights.parse("MOD"))
        assertEquals(Rights.ADMINISTRATOR, Rights.parse("administrator"))
        assertEquals(Rights.ADMINISTRATOR, Rights.parse("ADMIN"))
        assertEquals(Rights.ADMINISTRATOR, Rights.fromRightsColumn("MOD,ADMINISTRATOR"))
    }

    @Test
    fun ordering() {
        assertTrue(Rights.ADMINISTRATOR.isAtLeast(Rights.MODERATOR))
        assertFalse(Rights.MODERATOR.isAtLeast(Rights.ADMINISTRATOR))
        assertTrue(Rights.ADMINISTRATOR.isOneOf(Rights.ADMINISTRATOR, Rights.MODERATOR))
    }

    @Test
    fun wireName() {
        assertEquals("", Rights.NONE.wireName())
        assertEquals("MOD", Rights.MODERATOR.wireName())
        assertEquals("ADMINISTRATOR", Rights.ADMINISTRATOR.wireName())
        assertEquals("ADMINISTRATOR", Rights.MODERATOR.wireName(realmDevMode = true))
    }

    @Test
    fun satisfiesGate() {
        assertTrue(Rights.ADMINISTRATOR.satisfiesGate(Rights.MODERATOR))
        assertFalse(Rights.MODERATOR.satisfiesGate(Rights.ADMINISTRATOR))
    }
}
