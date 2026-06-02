package dev.or2.roles

/**
 * Bridges integer [Rights.level] storage and legacy `modlevel.*` text (DB migration, login wire, world gates).
 */
object LegacyModLevelMigration {
    fun rightsFromLevel(level: Int): Rights = Rights.fromLevel(level)

    /** Parses Central LOGIN_OK / legacy `modlevel.*` wire text into [Rights]. */
    fun rightsFromLegacyWire(raw: String?): Rights =
        rightsFromLevel(rightsLevelFromLegacyText(raw))

    fun donatorFromLevel(level: Int): DonatorRanks =
        DonatorRanks.entries.firstOrNull { it.level == level } ?: DonatorRanks.NONE

    fun gameModeFromLevel(level: Int): GameModes =
        GameModes.entries.firstOrNull { it.level == level } ?: GameModes.ADVENTURER

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
            Rights.MODERATOR -> "modlevel.moderator"
            Rights.SUPPORT -> "modlevel.moderator"
            Rights.ADMINISTRATOR -> "modlevel.admin"
            Rights.DEVELOPER -> "modlevel.dev"
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
            "modlevel.moderator" -> Rights.MODERATOR.level
            "modlevel.admin" -> Rights.ADMINISTRATOR.level
            "modlevel.dev" -> Rights.DEVELOPER.level
            "modlevel.owner" -> Rights.MANAGER.level
            else -> null
        }
}
