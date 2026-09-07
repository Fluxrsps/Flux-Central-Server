package dev.or2.central.embed

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadyMarkerTest {
    @Test
    fun `ready marker matches the pterodactyl egg`() {
        val egg = Path.of("..", "deploy", "pterodactyl", "egg-openrune-central.json")
        assertTrue(egg.exists(), "egg not found at ${egg.toAbsolutePath()}")

        val unescaped = egg.readText().replace("\\\"", "\"")
        val done = Regex("\"done\"\\s*:\\s*\"(.+?)\"").find(unescaped)?.groupValues?.get(1)

        assertEquals(CentralEmbeddedServer.READY_MARKER, done)
    }
}
