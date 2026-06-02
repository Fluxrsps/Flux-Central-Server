package dev.or2.central.util.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CentralRuntimeConfigDatabaseRequiredTest {

    @Test
    fun `resolveRequiredDatabaseConfig accepts jdbc url`() {
        val cfg =
            mergedConfigFromFlatForTest(
                mapOf(
                    "openrune.jdbc.url" to "jdbc:postgresql://db.example.com:5432/mydb",
                    "openrune.db.user" to "u",
                    "openrune.db.password" to "p",
                ),
            )

        val db = resolveRequiredDatabaseConfig(cfg)
        assertEquals("jdbc:postgresql://db.example.com:5432/mydb", db.jdbcUrl)
    }

    @Test
    fun `resolveRequiredDatabaseConfig composes from host port and name`() {
        val cfg =
            mergedConfigFromFlatForTest(
                mapOf(
                    "openrune.db.host" to "db.example.com",
                    "openrune.db.port" to "5433",
                    "openrune.db.name" to "central",
                    "openrune.db.user" to "u",
                    "openrune.db.password" to "p",
                ),
            )

        val db = resolveRequiredDatabaseConfig(cfg)
        assertEquals("jdbc:postgresql://db.example.com:5433/central", db.jdbcUrl)
    }

    @Test
    fun `resolveRequiredDatabaseConfig allows jdbc url without user when credentials not required`() {
        val cfg =
            mergedConfigFromFlatForTest(
                mapOf(
                    "openrune.jdbc.url" to "jdbc:postgresql://127.0.0.1:5432/x",
                ),
            )

        val db = resolveRequiredDatabaseConfig(cfg)
        assertEquals("", db.user)
        assertEquals("", db.password)
    }

    @Test
    fun `resolveRequiredDatabaseConfig allows local host without password`() {
        val cfg =
            mergedConfigFromFlatForTest(
                mapOf(
                    "openrune.db.host" to "127.0.0.1",
                    "openrune.db.name" to "central",
                    "openrune.db.user" to "openrune",
                ),
            )

        val db = resolveRequiredDatabaseConfig(cfg)
        assertEquals("jdbc:postgresql://127.0.0.1:5432/central", db.jdbcUrl)
        assertEquals("openrune", db.user)
        assertEquals("", db.password)
    }

    @Test
    fun `resolveRequiredDatabaseConfig rejects missing password when credentials required`() {
        val cfg =
            mergedConfigFromFlatForTest(
                mapOf(
                    "openrune.jdbc.url" to "jdbc:postgresql://127.0.0.1:5432/x",
                    "openrune.db.user" to "u",
                    "openrune.db.requireCredentials" to "true",
                ),
            )

        assertFailsWith<CentralConfigException> {
            resolveRequiredDatabaseConfig(cfg)
        }
    }

    @Test
    fun `resolveRequiredDatabaseConfig rejects missing host when no jdbc url`() {
        val cfg =
            mergedConfigFromFlatForTest(
                mapOf(
                    "openrune.db.name" to "central",
                    "openrune.db.user" to "u",
                    "openrune.db.password" to "p",
                ),
            )

        assertFailsWith<CentralConfigException> {
            resolveRequiredDatabaseConfig(cfg)
        }
    }
}
