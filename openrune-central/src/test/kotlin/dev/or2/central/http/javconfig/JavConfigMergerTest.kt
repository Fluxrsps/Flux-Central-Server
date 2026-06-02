package dev.or2.central.http.javconfig

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JavConfigMergerTest {

    @Test
    fun `lineKey parses simple param and msg lines`() {
        assertEquals("title", JavConfigMerger.lineKey("title=Blurite Alpha"))
        assertEquals("param=25", JavConfigMerger.lineKey("param=25=238"))
        assertEquals("msg=ok", JavConfigMerger.lineKey("msg=ok=OK"))
    }

    @Test
    fun merge_replaces_only_configured_keys() {
        val remote =
            """
            title=Remote
            codebase=http://remote/
            param=25=100
            """.trimIndent()

        val merged =
            JavConfigMerger.merge(
                remote,
                mapOf(
                    "title" to "Local",
                    "param=25" to "238",
                ),
            )

        assertEquals(
            """
            title=Local
            codebase=http://remote/
            param=25=238
            """.trimIndent(),
            merged,
        )
    }

    @Test
    fun merge_appends_unknown_override_keys() {
        val remote = "title=Remote"
        val merged = JavConfigMerger.merge(remote, mapOf("cachedir" to "mine"))
        assertEquals("title=Remote\ncachedir=mine", merged)
    }

    @Test
    fun parseOverrideEntryLine_reads_full_jav_lines() {
        assertEquals(
            "param=25" to "240",
            JavConfigMerger.parseOverrideEntryLine("param=25=240"),
        )
        assertNull(JavConfigMerger.parseOverrideEntryLine("not-a-line"))
    }

    @Test
    fun configPropSuffixToLineKey_maps_dots_for_param_and_msg() {
        assertEquals("param=25", JavConfigMerger.configPropSuffixToLineKey("param.25"))
        assertEquals("msg=ok", JavConfigMerger.configPropSuffixToLineKey("msg.ok"))
        assertEquals("codebase", JavConfigMerger.configPropSuffixToLineKey("codebase"))
    }
}
