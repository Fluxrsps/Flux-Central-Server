package dev.or2.roles

enum class DonatorRanks(val icon: Int, val useModIconSprites: Boolean = true, val level: Int) {
    NONE(-1, level = 0),
    SAPPHIRE(50,level = 1),
    EMERALD(51,level = 2),
    RUBY(52,level = 3),
    DIAMOND(53,level = 4),
    DRAGONSTONE(54,level = 5),
    ONYX(55,level = 6),
    ZENYTE(56,level = 7),
    ETERNAL(57,level = 8);

    companion object {
        fun find(level: Int) = entries.firstOrNull { it.level == level }?: NONE
    }

}