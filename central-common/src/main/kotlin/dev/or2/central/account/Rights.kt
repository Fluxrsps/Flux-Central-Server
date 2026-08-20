package dev.or2.central.account

enum class Rights(
    val icon: Int,
    val clientCode: Int,
    val useModIconSprites: Boolean,
    val level: Int
) {
    NONE(-1,0,true,0),
    SUPPORT(65413,1,false,1),
    MODERATOR(65417,1,false,2),
    ADMINISTRATOR(65424,2,false,3),
    DEVELOPER(65423,2,false,4),
    MANAGER(65418,2,false,5);
    ;

    public fun isAtLeast(other: Rights): Boolean = level >= other.level

    public fun isHigherThan(other: Rights): Boolean = level > other.level

    public fun isLowerThan(other: Rights): Boolean = level < other.level

    public fun isOneOf(vararg privileges: Rights): Boolean = this in privileges

    public fun forWire(realmDevMode: Boolean = false): Rights = if (realmDevMode) ADMINISTRATOR else this

    public fun wireName(realmDevMode: Boolean = false): String {
        val effective = forWire(realmDevMode)
        return if (effective == NONE) "" else effective.name
    }

    public fun satisfiesGate(required: Rights): Boolean = isAtLeast(required)

    public companion object {
        public fun fromLevel(level: Int): Rights = entries.firstOrNull { it.level == level } ?: NONE

        public fun parse(name: String): Rights {
            val normalized = name.trim()
            if (normalized.equals("ADMIN", ignoreCase = true)) return ADMINISTRATOR
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: NONE
        }

        public fun fromRightsColumn(raw: String?): Rights {
            if (raw.isNullOrBlank()) return NONE
            var best = NONE
            for (part in raw.split(',')) {
                val parsed = parse(part)
                if (parsed.level > best.level) best = parsed
            }
            return best
        }
    }
}
