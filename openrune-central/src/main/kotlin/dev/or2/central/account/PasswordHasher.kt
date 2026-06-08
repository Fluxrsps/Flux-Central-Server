package dev.or2.central.account

import at.favre.lib.crypto.bcrypt.BCrypt

class PasswordHasher {
    fun verify(
        storedHash: String,
        plainPassword: String,
    ): Boolean {
        val hash = storedHash.trim()
        if (hash.isEmpty()) return false
        return BCrypt.verifyer().verify(plainPassword.toCharArray(), hash.toByteArray()).verified
    }

    fun hash(plainPassword: String): String =
        BCrypt.with(BCrypt.Version.VERSION_2Y).hashToString(BCRYPT_COST, plainPassword.toCharArray())

    private companion object {
        private const val BCRYPT_COST = 12
    }
}
