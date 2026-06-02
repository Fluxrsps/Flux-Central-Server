package dev.or2.central.server.console

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CentralConsoleCommandsTest {

    @Test
    fun `parseRefreshScope defaults to all`() {
        assertEquals(RefreshScope.ALL, parseRefreshScope(emptyList()))
        assertEquals(RefreshScope.ALL, parseRefreshScope(listOf("all")))
    }

    @Test
    fun `parseRefreshScope resolves granular targets`() {
        assertEquals(RefreshScope.ONLINE_PLAYERS, parseRefreshScope(listOf("onlineplayers")))
        assertEquals(RefreshScope.ONLINE_PLAYERS, parseRefreshScope(listOf("online", "players")))
        assertEquals(RefreshScope.WORLDS, parseRefreshScope(listOf("worldslist")))
        assertEquals(RefreshScope.JAV_CONFIG, parseRefreshScope(listOf("jav_config")))
        assertEquals(RefreshScope.BAD_WORDS, parseRefreshScope(listOf("badwords")))
    }

    @Test
    fun `parseRefreshScope rejects unknown target`() {
        assertFailsWith<IllegalArgumentException> {
            parseRefreshScope(listOf("not-a-cache"))
        }
    }

    @Test
    fun `refresh all runs every step`() {
        var count = 0
        val registry =
            CentralRefreshRegistry(
                listOf(
                    step("worlds") { count++ },
                    step("jav") { count++ },
                ),
            )
        CentralConsoleCommands(registry, shutdown = {}).handleLine("refresh all")
        assertEquals(2, count)
    }

    @Test
    fun `refresh onlineplayers runs one step`() {
        var online = false
        var worlds = false
        val registry =
            CentralRefreshRegistry(
                listOf(
                    step("worlds") { worlds = true },
                    step("online") { online = true },
                ),
            )
        CentralConsoleCommands(registry, shutdown = {}).handleLine("refresh onlineplayers")
        assertTrue(online)
        assertEquals(false, worlds)
    }

    @Test
    fun `refreshcache runs all`() {
        var count = 0
        val registry =
            CentralRefreshRegistry(
                listOf(
                    step("a") { count++ },
                    step("b") { count++ },
                ),
            )
        CentralConsoleCommands(registry, shutdown = {}).handleLine("refreshcache")
        assertEquals(2, count)
    }

    private fun step(key: String, block: () -> Unit) =
        CentralRefreshStep(keys = setOf(key), displayName = key, block = block)
}
