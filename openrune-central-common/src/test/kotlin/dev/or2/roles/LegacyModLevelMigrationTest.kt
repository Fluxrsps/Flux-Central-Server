package dev.or2.roles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyModLevelMigrationTest {
    @Test
    fun legacyRightsTextMapsToHighestLevel() {
        assertEquals(Rights.NONE.level, LegacyModLevelMigration.rightsLevelFromLegacyText(""))
        assertEquals(Rights.NONE.level, LegacyModLevelMigration.rightsLevelFromLegacyText("modlevel.player"))
        assertEquals(Rights.MOD.level, LegacyModLevelMigration.rightsLevelFromLegacyText("modlevel.moderator"))
        assertEquals(Rights.ADMIN.level, LegacyModLevelMigration.rightsLevelFromLegacyText("modlevel.admin"))
        assertEquals(Rights.MANAGER.level, LegacyModLevelMigration.rightsLevelFromLegacyText("modlevel.owner"))
        assertEquals(
            Rights.MANAGER.level,
            LegacyModLevelMigration.rightsLevelFromLegacyText("modlevel.player,modlevel.owner"),
        )
    }

    @Test
    fun wireAndGateUseLevels() {
        assertEquals("modlevel.admin", LegacyModLevelMigration.rightsWireFromLevel(Rights.ADMIN.level))
        assertEquals("", LegacyModLevelMigration.rightsWireFromLevel(Rights.NONE.level))
        assertTrue(LegacyModLevelMigration.rightsLevelMeetsModLevelToken(Rights.ADMIN.level, "modlevel.moderator"))
        assertTrue(!LegacyModLevelMigration.rightsLevelMeetsModLevelToken(Rights.MOD.level, "modlevel.admin"))
    }
}
