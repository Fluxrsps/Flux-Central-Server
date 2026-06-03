package dev.or2.roles

enum class GameModes(val icon: Int, val useModIconSprites: Boolean = false, val level: Int = 0) {
    ADVENTURER(-1, level = 0),
    IRONMAN(65410, level = 1),
    ULTIMATE_IRONMAN(65409, level = 2),
    HARDCORE_IRONMAN(65408, level = 3),
    GROUP_IRONMAN(65407, level = 4),
    HARDCORE_GROUP_IRONMAN(65406, level = 5),
    UNRANKED_GROUP_IRONMAN(65405, level = 6);

    companion object {
        fun find(level: Int) = GameModes.entries.firstOrNull { it.level == level }?: ADVENTURER
    }
}