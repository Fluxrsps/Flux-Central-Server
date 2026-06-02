package dev.or2.roles

enum class Rights(val icon: Int, val clientCode: Int, val useModIconSprites: Boolean, val level: Int) {
    NONE(-1,0,true,0),
    SUPPORT(1,1,true,1),
    MODERATOR(0,1,true,2),
    ADMINISTRATOR(1,2,true,3),
    DEVELOPER(1,2,true,4),
    MANAGER(1,2,true,5);

    companion object {
        fun fromLevel(level: Int): Rights = entries.firstOrNull { it.level == level } ?: NONE
    }

    fun isAtLeast(other: Rights): Boolean {
        return level >= other.level
    }

    fun isHigherThan(other: Rights): Boolean {
        return level > other.level
    }

    fun isLowerThan(other: Rights): Boolean {
        return level < other.level
    }

    fun isOneOf(vararg privileges: Rights): Boolean {
        return this in privileges
    }

}