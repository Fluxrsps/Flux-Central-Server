package dev.or2.roles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyModLevelMigrationTest {
    @Test
    fun legacyRightsTextMapsToHighestLevel() {
        assertEquals(Rights.NONE.level, LegacyModLevelMigration.rightsLevelFromLegacyText(""))
        assertEquals(Rights.NONE.level, LegacyModLevelMigration.rightsLevelFromLegacyText("modlevel.player"))
        assertEquals(Rights.MODERATOR.level, LegacyModLevelMigration.rightsLevelFromLegacyText("modlevel.moderator"))
        assertEquals(Rights.ADMINISTRATOR.level, LegacyModLevelMigration.rightsLevelFromLegacyText("modlevel.admin"))
        assertEquals(Rights.DEVELOPER.level, LegacyModLevelMigration.rightsLevelFromLegacyText("modlevel.dev"))
        assertEquals(Rights.MANAGER, LegacyModLevelMigration.rightsFromLegacyWire("modlevel.owner"))
        assertEquals(Rights.MANAGER.level, LegacyModLevelMigration.rightsLevelFromLegacyText("modlevel.owner"))
        assertEquals(
            Rights.MANAGER.level,
            LegacyModLevelMigration.rightsLevelFromLegacyText("modlevel.player,modlevel.owner"),
        )
    }

    @Test
    fun wireAndGateUseLevels() {
        assertEquals("modlevel.admin", LegacyModLevelMigration.rightsWireFromLevel(Rights.ADMINISTRATOR.level))
        assertEquals("modlevel.dev", LegacyModLevelMigration.rightsWireFromLevel(Rights.DEVELOPER.level))
        assertEquals("", LegacyModLevelMigration.rightsWireFromLevel(Rights.NONE.level))
        assertTrue(LegacyModLevelMigration.rightsLevelMeetsModLevelToken(Rights.ADMINISTRATOR.level, "modlevel.moderator"))
        assertTrue(!LegacyModLevelMigration.rightsLevelMeetsModLevelToken(Rights.MODERATOR.level, "modlevel.admin"))
    }
}
