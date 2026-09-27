package dev.or2.central.exchange

import kotlin.test.Test
import kotlin.test.assertEquals

class TaxPolicyTest {
    @Test
    fun twoPercentOfHundredIsTwo() {
        assertEquals(2, TaxPolicy.unitTax(100, 200, 5_000_000, exempt = false))
    }

    @Test
    fun belowFiftyGpPaysNothingAtTwoPercent() {
        assertEquals(0, TaxPolicy.unitTax(49, 200, 5_000_000, exempt = false))
        assertEquals(1, TaxPolicy.unitTax(50, 200, 5_000_000, exempt = false))
    }

    @Test
    fun perItemCapApplies() {
        assertEquals(5_000_000, TaxPolicy.unitTax(1_200_000_000, 200, 5_000_000, exempt = false))
    }

    @Test
    fun exemptItemsPayNothing() {
        assertEquals(0, TaxPolicy.unitTax(1_000_000, 200, 5_000_000, exempt = true))
    }

    @Test
    fun splitIntoManyFillsCostsTheSame() {
        val unit = TaxPolicy.unitTax(137, 200, 5_000_000, exempt = false)
        val whole = TaxPolicy.fillTax(unit, 900)
        val parts = listOf(100L, 250L, 300L, 250L).sumOf { TaxPolicy.fillTax(unit, it) }
        assertEquals(whole, parts)
    }

    @Test
    fun matchesFloorForLargePricesWithoutOverflow() {
        val price = 900_000_000_000_000L
        val expected = java.math.BigInteger.valueOf(price).multiply(java.math.BigInteger.valueOf(200))
            .divide(java.math.BigInteger.valueOf(10_000)).toLong()
        assertEquals(minOf(expected, Long.MAX_VALUE), TaxPolicy.unitTax(price, 200, Long.MAX_VALUE, exempt = false))
    }
}
