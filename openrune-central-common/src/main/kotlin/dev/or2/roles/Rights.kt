package dev.or2.roles

enum class Rights(val icon: Int, val clientCode: Int, val useModIconSprites: Boolean, val level: Int) {
    NONE(-1,0,true,0),
    SUPPORT(65413,1,false,1),
    MODERATOR(65417,1,false,2),
    ADMINISTRATOR(65424,2,false,3),
    DEVELOPER(65423,2,false,4),
    MANAGER(65418,2,false,5);

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