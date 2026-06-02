package dev.or2.central.util.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CentralRuntimeConfigJdbcTest {

    @Test
    fun `mergeJavConfigOverrideLines parses pasted jav block`() {
        val result = linkedMapOf<String, String>()
        mergeJavConfigOverrideLines(
            result,
            """
            title=Fluxious
            param=25=238
            param=17=http://central.example.com/worldslist.ws
            msg=ok=OK
            """.trimIndent(),
        )
        assertEquals("Fluxious", result["title"])
        assertEquals("238", result["param=25"])
        assertEquals("http://central.example.com/worldslist.ws", result["param=17"])
        assertEquals("OK", result["msg=ok"])
    }

    @Test
    fun `resolveJdbcUrl without require throws when unconfigured`() {
        assertFailsWith<CentralConfigException> {
            resolveJdbcUrl(mergedConfigFromFlatForTest(emptyMap()), requirePartsWhenUrlMissing = false)
        }
    }
}
