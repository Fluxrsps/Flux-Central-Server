package dev.or2.central.exchange

/**
 * Items the Grand Exchange does not tax, by the display name the OSRS wiki mapping feed uses.
 * Matched at import time, which is the only point Central sees a name; the flag then lives on
 * `exchange_items.tax_exempt` and staff can extend it from there.
 */
object TaxExemptItems {
    private val names: Set<String> =
        setOf(
            "Old school bond",
            "Energy potion(4)",
            "Energy potion(3)",
            "Energy potion(2)",
            "Energy potion(1)",
            "Bronze arrow",
            "Bronze dart",
            "Iron arrow",
            "Iron dart",
            "Mind rune",
            "Steel arrow",
            "Steel dart",
            "Bass",
            "Bread",
            "Cake",
            "Cooked chicken",
            "Cooked meat",
            "Herring",
            "Lobster",
            "Mackerel",
            "Meat pie",
            "Pike",
            "Salmon",
            "Shrimps",
            "Tuna",
            "Ardougne teleport (tablet)",
            "Camelot teleport (tablet)",
            "Civitas illa fortis teleport",
            "Falador teleport (tablet)",
            "Games necklace(8)",
            "Kourend castle teleport (tablet)",
            "Lumbridge teleport (tablet)",
            "Ring of dueling(8)",
            "Teleport to house (tablet)",
            "Varrock teleport (tablet)",
            "Chisel",
            "Gardening trowel",
            "Glassblowing pipe",
            "Hammer",
            "Needle",
            "Pestle and mortar",
            "Rake",
            "Saw",
            "Secateurs",
            "Seed dibber",
            "Shears",
            "Spade",
            "Watering can",
        ).mapTo(HashSet(), String::lowercase)

    fun contains(name: String): Boolean = name.trim().lowercase() in names
}
