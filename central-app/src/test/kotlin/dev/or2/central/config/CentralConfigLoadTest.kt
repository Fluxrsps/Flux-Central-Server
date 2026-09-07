package dev.or2.central.config

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CentralConfigLoadTest {
    private fun writeConfig(yaml: String): Path {
        val file = createTempDirectory("central-config-test").resolve("central-config.yaml")
        file.writeText(yaml)
        return file
    }

    @Test
    fun `reads values from the yaml file`() {
        val file =
            writeConfig(
                """
                openrune:
                  serverName: Fluxious
                  http:
                    port: 9090
                  database:
                    host: '85.155.190.128'
                    port: 5432
                    name: 'openrune_central'
                    user: 'fluxious'
                    password: 'secret'
                    poolSize: 7
                  session:
                    ttlMs: 60000
                  worldLink:
                    port: 9091
                  javConfig:
                    revision: 239
                    configProps:
                      title: 'Fluxious'
                      param: '17=https://central.fluxious-rsps.com/worldslist.ws'
                """.trimIndent(),
            )

        val config = CentralConfig.load(file, required = true)

        assertEquals("Fluxious", config.serverName)
        assertEquals(9090, config.http.port)
        assertEquals("85.155.190.128", config.database.host)
        assertEquals("openrune_central", config.database.name)
        assertEquals("fluxious", config.database.user)
        assertEquals("secret", config.database.password)
        assertEquals(7, config.database.poolSize)
        assertEquals(60_000L, config.session.ttlMs)
        assertEquals(9091, config.worldLink.port)
        assertEquals(239, config.javConfig.revision)
        assertEquals(
            "17=https://central.fluxious-rsps.com/worldslist.ws",
            config.javConfig.configProps["param"],
        )
    }

    @Test
    fun `resolves the jdbc url from the file instead of falling back to defaults`() {
        val file =
            writeConfig(
                """
                openrune:
                  database:
                    host: db.example.com
                    port: 5432
                    name: openrune_central
                    user: openrune
                    password: pw
                """.trimIndent(),
            )

        val config = CentralConfig.load(file, required = true)

        assertTrue(config.database.host.isNotBlank(), "database.host must come from the file")
        assertEquals(
            "jdbc:postgresql://db.example.com:5432/openrune_central",
            config.resolvedJdbcUrl(),
        )
    }

    @Test
    fun `jdbc url wins over host and name`() {
        val file =
            writeConfig(
                """
                openrune:
                  jdbc:
                    url: 'jdbc:postgresql://85.155.190.128:5432/openrune_central'
                  database:
                    host: ignored.example.com
                    name: ignored
                    user: fluxious
                    password: pw
                """.trimIndent(),
            )

        val config = CentralConfig.load(file, required = true)

        assertEquals("jdbc:postgresql://85.155.190.128:5432/openrune_central", config.resolvedJdbcUrl())
        assertEquals("fluxious", config.resolvedDbUser())
        assertEquals("pw", config.resolvedDbPassword())
    }

    @Test
    fun `missing required file is an error`() {
        val missing = createTempDirectory("central-config-test").resolve("absent.yaml")

        assertFailsWith<IllegalStateException> { CentralConfig.load(missing, required = true) }
    }

    @Test
    fun `missing optional file falls back to defaults`() {
        val missing = createTempDirectory("central-config-test").resolve("absent.yaml")

        val config = CentralConfig.load(missing, required = false)

        assertEquals("", config.database.host)
        assertEquals(8080, config.http.port)
    }
}
