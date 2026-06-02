package dev.or2.roles

enum class Rights(val icon: Int, val clientCode: Int, val useModIconSprites: Boolean, val level: Int) {
    NONE(-1,0,true,0),
    MOD(0,1,true,1),
    ADMIN(1,2,true,2),
    DEV(1,2,true,3),
    MANAGER(1,2,true,4);

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