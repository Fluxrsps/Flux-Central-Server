package dev.or2.central.util.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudflaredTunnelEnvTest {

    @Test
    fun `isConfigured requires status and token`() {
        assertFalse(CloudflaredTunnelEnv.isConfigured(mergedConfigFromFlatForTest(emptyMap())))

        val statusOnly =
            mergedConfigFromFlatForTest(
                mapOf("openrune.cloudflared.status" to "1"),
            )
        assertFalse(CloudflaredTunnelEnv.isConfigured(statusOnly))

        val both =
            mergedConfigFromFlatForTest(
                mapOf(
                    "openrune.cloudflared.status" to "1",
                    "openrune.cloudflared.token" to "eyJhbGciOiJIUzI1NiJ9.test",
                ),
            )
        assertTrue(CloudflaredTunnelEnv.isConfigured(both))
    }

    @Test
    fun `parseEnabled accepts common truthy values`() {
        assertTrue(parseEnabled("1"))
        assertTrue(parseEnabled("true"))
        assertFalse(parseEnabled("0"))
        assertFalse(parseEnabled(null))
    }
}
