package dev.or2.roles

enum class GameModes(val icon: Int, val useModIconSprites: Boolean = true, val level: Int = 0) {
    ADVENTURER(-1, level = 0),
    VETERAN(57, level = 1),
    IRONMAN(2, level = 2),
    ULTIMATE_IRONMAN(3, level = 3),
    HARDCORE_IRONMAN(10, level = 4),
    GROUP_IRONMAN(47, level = 5),
    HARDCORE_GROUP_IRONMAN(48, level = 6),
    UNRANKED_GROUP_IRONMAN(49, level = 7);

    companion object {
        fun find(level: Int) = GameModes.entries.firstOrNull { it.level == level }?: ADVENTURER
    }
}