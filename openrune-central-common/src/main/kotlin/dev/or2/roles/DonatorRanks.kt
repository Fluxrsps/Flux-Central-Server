package dev.or2.roles

enum class DonatorRanks(val icon: Int, val useModIconSprites: Boolean = false, val level: Int) {
    NONE(-1, level = 0),
    SAPPHIRE(65414,level = 1),
    EMERALD(65420,level = 2),
    RUBY(65415,level = 3),
    DIAMOND(65422,level = 4),
    DRAGONSTONE(65421,level = 5),
    ONYX(65416,level = 6),
    ZENYTE(65412,level = 7),
    ETERNAL(65419,level = 8);

    companion object {
        fun find(level: Int) = entries.firstOrNull { it.level == level }?: NONE
    }

}