package dev.or2.central.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import de.mkammerer.argon2.Argon2
import de.mkammerer.argon2.Argon2Factory

interface PasswordHasher {
    fun hash(plainPassword: String): String

    fun verify(storedHash: String, plainPassword: String): Boolean

    /** True when [storedHash] uses stronger Argon2 params than this hasher would produce now. */
    fun shouldUpgrade(storedHash: String): Boolean
}

class CompositePasswordHasher(
    private val defaultAlgorithm: String,
    bcryptCost: Int,
    argon2Iterations: Int = ARGON2_ITERATIONS_DEFAULT,
    argon2MemoryKib: Int = ARGON2_MEMORY_KIB_DEFAULT,
) : PasswordHasher {
    private val argon2: Argon2 by lazy { Argon2Factory.create() }
    private val cost = bcryptCost.coerceIn(4, 31)
    private val argon2TimeCost = argon2Iterations.coerceIn(1, 100)
    private val argon2Memory = argon2MemoryKib.coerceIn(8, 524_288)

    override fun hash(plainPassword: String): String =
        when (defaultAlgorithm.lowercase()) {
            "bcrypt" -> hashBcrypt(plainPassword)
            "bcrypt2y" -> hashBcrypt2Y(plainPassword)
            else -> hashArgon2(plainPassword)
        }

    override fun verify(storedHash: String, plainPassword: String): Boolean {
        val h = storedHash.trim()
        if (h.isEmpty()) return false
        return when {
            h.startsWith("\$argon2") -> verifyArgon2(h, plainPassword)
            h.startsWith("\$2") -> verifyBcrypt(h, plainPassword)
            else -> false
        }
    }

    private fun hashBcrypt2Y(plainPassword: String): String =
            BCrypt.with(BCrypt.Version.VERSION_2Y)
           .hashToString(cost, plainPassword.toCharArray())

    override fun shouldUpgrade(storedHash: String): Boolean {
        val h = storedHash.trim()
        if (defaultAlgorithm.lowercase() in setOf("bcrypt", "bcrypt2y") && h.startsWith("\$argon2")) {
            return true
        }
        val params = parseArgon2Params(storedHash) ?: return false
        return params.first > argon2TimeCost || params.second > argon2Memory
    }

    private fun hashArgon2(plainPassword: String): String {
        val pw = plainPassword.toCharArray()
        return try {
            argon2.hash(argon2TimeCost, argon2Memory, ARGON2_PARALLELISM, pw)
        } finally {
            argon2.wipeArray(pw)
        }
    }

    private fun verifyArgon2(stored: String, plainPassword: String): Boolean {
        val pw = plainPassword.toCharArray()
        return try {
            argon2.verify(stored, pw)
        } catch (_: IllegalArgumentException) {
            false
        } finally {
            argon2.wipeArray(pw)
        }
    }

    private fun hashBcrypt(plainPassword: String): String =
        BCrypt.withDefaults().hashToString(cost, plainPassword.toCharArray())

    private fun verifyBcrypt(stored: String, plainPassword: String): Boolean =
        BCrypt.verifyer().verify(plainPassword.toCharArray(), stored.toCharArray()).verified

    private fun parseArgon2Params(stored: String): Pair<Int, Int>? {
        if (!stored.startsWith("\$argon2")) {
            return null
        }
        val params = stored.substringAfter('$').substringAfter('$').substringAfter('$')
        val memory = params.substringAfter("m=").substringBefore(',').toIntOrNull() ?: return null
        val iterations = params.substringAfter("t=").substringBefore(',').toIntOrNull() ?: return null
        return iterations to memory
    }

    private companion object {
        private const val ARGON2_ITERATIONS_DEFAULT = 20
        private const val ARGON2_MEMORY_KIB_DEFAULT = 65536
        private const val ARGON2_PARALLELISM = 1
    }
}
