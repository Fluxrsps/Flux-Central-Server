package dev.or2.roles

/**
 * Bridges integer [Rights.level] storage and legacy `modlevel.*` text (DB migration, login wire, world gates).
 */
object LegacyModLevelMigration {
    fun rightsFromLevel(level: Int): Rights =
        Rights.entries.firstOrNull { it.level == level } ?: Rights.NONE

    fun donatorFromLevel(level: Int): DonatorRanks =
        DonatorRanks.entries.firstOrNull { it.level == level } ?: DonatorRanks.NONE

    fun gameModeFromLevel(level: Int): GameMode =
        GameMode.entries.firstOrNull { it.level == level } ?: GameMode.ADVENTURER

    /** Highest privilege found in legacy comma-separated `modlevel.*` text. */
    fun rightsLevelFromLegacyText(raw: String?): Int {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) {
            return Rights.NONE.level
        }
        var max = Rights.NONE.level
        for (part in s.split(',')) {
            val token = part.trim()
            val level = rightsLevelFromModLevelToken(token) ?: continue
            if (level > max) {
                max = level
            }
        }
        return max
    }

    fun rightsWireFromLevel(level: Int): String =
        when (rightsFromLevel(level)) {
            Rights.NONE -> ""
            Rights.MOD -> "modlevel.moderator"
            Rights.ADMIN -> "modlevel.admin"
            Rights.DEV -> "modlevel.admin"
            Rights.MANAGER -> "modlevel.owner"
        }

    fun rightsLevelMeetsModLevelToken(
        accountLevel: Int,
        token: String,
    ): Boolean {
        val required = rightsLevelFromModLevelToken(token.trim().lowercase()) ?: return false
        return accountLevel >= required
    }

    private fun rightsLevelFromModLevelToken(token: String): Int? =
        when (token) {
            "modlevel.player", "" -> Rights.NONE.level
            "modlevel.moderator" -> Rights.MOD.level
            "modlevel.admin" -> Rights.ADMIN.level
            "modlevel.dev" -> Rights.DEV.level
            "modlevel.owner" -> Rights.MANAGER.level
            else -> null
        }
}
