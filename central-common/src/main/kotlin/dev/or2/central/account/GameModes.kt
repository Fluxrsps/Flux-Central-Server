package dev.or2.central.account

enum class GameModes(
    val icon: Int,
    val useModIconSprites: Boolean = true,
    val level: Int = 0,
    val isIronman: Boolean = false
) {
    ADVENTURER(icon = -1, level = 0),
    IRONMAN(icon = 2, level = 1, isIronman = true),
    ULTIMATE_IRONMAN(icon = 3, level = 2, isIronman = true),
    HARDCORE_IRONMAN(icon = 10, level = 3, isIronman = true),
    GROUP_IRONMAN(icon = 47, level = 4, isIronman = true),
    HARDCORE_GROUP_IRONMAN(icon = 48, level = 5, isIronman = true),
    UNRANKED_GROUP_IRONMAN(icon = 49, level = 6, isIronman = true),

    // Reserved engine-specific game mode.
    // Levels 7-19 are left available for future OSRS game modes.
    VETERAN(icon = 57, level = 20);

    companion object {
        fun find(level: Int): GameModes = entries.firstOrNull { it.level == level } ?: ADVENTURER
    }
}