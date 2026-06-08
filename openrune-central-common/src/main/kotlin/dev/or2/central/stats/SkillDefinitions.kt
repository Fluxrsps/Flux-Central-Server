package dev.or2.central.stats

object SkillDefinitions {
    val orderedSkillIds: IntArray =
        intArrayOf(
            0, 1, 2, 3, 4, 5, 6,
            7, 8, 9, 10, 11, 12,
            13, 14, 15, 16, 17, 18,
            19, 20, 21, 22, 23,
        )

    private val namesById: Map<Int, String> =
        mapOf(
            0 to "Attack",
            1 to "Defence",
            2 to "Strength",
            3 to "Hitpoints",
            4 to "Ranged",
            5 to "Prayer",
            6 to "Magic",
            7 to "Cooking",
            8 to "Woodcutting",
            9 to "Fletching",
            10 to "Fishing",
            11 to "Firemaking",
            12 to "Crafting",
            13 to "Smithing",
            14 to "Mining",
            15 to "Herblore",
            16 to "Agility",
            17 to "Thieving",
            18 to "Slayer",
            19 to "Farming",
            20 to "Runecrafting",
            21 to "Hunter",
            22 to "Construction",
            23 to "Sailing",
        )

    fun name(statId: Int): String = namesById[statId] ?: "Unknown"
}
