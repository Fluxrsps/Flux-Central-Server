package dev.or2.central.exchange

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExchangeConfigTest {
    @Test
    fun overridesReplaceOnlyTheirKeys() {
        val cfg =
            ExchangeConfigLoader.merge(
                mapOf(
                    "taxRateBps" to Json.parseToJsonElement("100"),
                    "unknownKey" to Json.parseToJsonElement("true"),
                ),
            )
        assertEquals(100, cfg.taxRateBps)
        assertEquals(ExchangeConfig.DEFAULT.maxActiveOrders, cfg.maxActiveOrders)
    }

    @Test
    fun brokenValuesAreRefused() {
        assertFailsWith<IllegalArgumentException> {
            ExchangeConfigLoader.merge(mapOf("taxRateBps" to Json.parseToJsonElement("-1")))
        }
        assertFailsWith<IllegalArgumentException> {
            ExchangeConfigLoader.merge(mapOf("maxFillsPerTransaction" to Json.parseToJsonElement("0")))
        }
    }

    @Test
    fun categoryRateFallsBackToDefault() {
        val cfg = ExchangeConfig(taxRateByCategoryBps = mapOf("HIGH_VALUE" to 100))
        assertEquals(100, cfg.rateBpsFor("HIGH_VALUE"))
        assertEquals(200, cfg.rateBpsFor("REGULAR"))
    }
}
