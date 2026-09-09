package dev.or2.central.http

import kotlin.test.Test
import kotlin.test.assertEquals

class JavConfigMergeTest {
    private val remote =
        """
        title=Old School RuneScape
        msg=ok=OK
        param=17=https://client.blurite.io/world_list.ws
        param=25=240
        param=10=5
        """.trimIndent()

    @Test
    fun `replaces the matching param line instead of appending a duplicate`() {
        val merged =
            JavConfigCache.mergeProps(
                remote,
                mapOf("param=17" to "https://central.fluxious-rsps.com/worldslist.ws"),
            )

        assertEquals(
            """
            title=Old School RuneScape
            msg=ok=OK
            param=17=https://central.fluxious-rsps.com/worldslist.ws
            param=25=240
            param=10=5
            """.trimIndent(),
            merged,
        )
    }

    @Test
    fun `accepts the legacy param key with the index inside the value`() {
        val merged =
            JavConfigCache.mergeProps(
                remote,
                mapOf("param" to "17=https://central.fluxious-rsps.com/worldslist.ws"),
            )

        assertEquals(
            "param=17=https://central.fluxious-rsps.com/worldslist.ws",
            merged.lines()[2],
        )
        assertEquals(5, merged.lines().size)
    }

    @Test
    fun `keys msg lines by their second segment`() {
        val merged = JavConfigCache.mergeProps(remote, mapOf("msg=ok" to "Confirm"))

        assertEquals("msg=ok=Confirm", merged.lines()[1])
        assertEquals(5, merged.lines().size)
    }

    @Test
    fun `replaces plain keys and appends unknown ones`() {
        val merged =
            JavConfigCache.mergeProps(
                remote,
                mapOf("title" to "OpenRune Central", "param=30" to "1"),
            )

        assertEquals("title=OpenRune Central", merged.lines().first())
        assertEquals("param=30=1", merged.lines().last())
        assertEquals(6, merged.lines().size)
    }
}
