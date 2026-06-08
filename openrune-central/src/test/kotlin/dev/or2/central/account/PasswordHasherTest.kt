package dev.or2.central.account

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordHasherTest {
    private val hasher = PasswordHasher()

    @Test
    fun bcryptRoundTrip() {
        val stored = hasher.hash("my-secret")
        assertTrue(stored.startsWith("\$2y\$12\$"))
        assertTrue(hasher.verify(stored, "my-secret"))
        assertFalse(hasher.verify(stored, "wrong"))
    }
}
