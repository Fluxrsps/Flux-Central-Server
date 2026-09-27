package dev.or2.central.exchange.pricing

import dev.or2.central.exchange.PricingConfig
import dev.or2.central.exchange.model.OrderSource
import dev.or2.central.exchange.model.Side
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketPriceCalculatorTest {
    private val now: Instant = Instant.parse("2026-09-26T12:00:00Z")
    private val config = PricingConfig(vwapHalfLifeHours = 1e9, minOrderAgeMinutes = 0, maxAccountWeightBps = 10_000)
    private var nextAccount = 1

    @Test
    fun vwapWeightsByQuantity() {
        val trades = listOf(trade(100, 10), trade(200, 30))
        val r = compute(trades, oldPrice = 150, config = config.copy(smoothing = 1.0, anchorMaxWeight = 0.0, maxChangeHighBps = 10_000, maxChangeLowBps = 10_000))
        assertEquals(175.0, r.vwap!!, 1e-9)
    }

    @Test
    fun decayFavoursRecentTrades() {
        val cfg = config.copy(vwapHalfLifeHours = 1.0)
        val trades = listOf(trade(100, 10, ageHours = 10.0), trade(200, 10, ageHours = 0.0))
        val r = compute(trades, oldPrice = 150, config = cfg)
        assertTrue(r.vwap!! > 190, "old trade should barely count, vwap was ${r.vwap}")
    }

    @Test
    fun outliersAreExcludedUnlessManyAccountsAgree() {
        val normal = (1..5).map { trade(100, 1) }
        val single = compute(normal + trade(1000, 1), oldPrice = 100)
        assertEquals(1, single.tradesExcluded)
        assertEquals(5, single.tradesUsed)

        val crowd = (1..10).map { trade(1000, 1) }
        val real = compute(normal + crowd, oldPrice = 100)
        assertEquals(0, real.tradesExcluded)
        assertEquals(15, real.tradesUsed)
    }

    @Test
    fun oneAccountCannotDominate() {
        val whale = 999
        val whaleTrades = (1..10).map { trade(200, 100, buyer = whale) }
        val others = (1..10).map { trade(100, 10) }
        val r = compute(whaleTrades + others, oldPrice = 150, config = config.copy(maxAccountWeightBps = 2500))
        assertTrue(r.vwap!! < 140, "whale should be capped to a quarter of the evidence, vwap was ${r.vwap}")
        val uncapped = compute(whaleTrades + others, oldPrice = 150)
        assertTrue(uncapped.vwap!! > 180, "without the cap the whale sets the price, vwap was ${uncapped.vwap}")
    }

    @Test
    fun selfLinkedSystemAndReversedTradesNeverCount() {
        val me = 7
        val trades =
            listOf(
                trade(500, 100, buyer = me, seller = me),
                trade(500, 100, flags = listOf(PriceTrade.LINKED_FLAG)),
                trade(500, 100, source = OrderSource.SYSTEM),
                trade(500, 100, source = OrderSource.ADMIN),
                trade(500, 100, flags = listOf(PriceTrade.REVERSED_FLAG)),
                trade(100, 1),
            )
        val r = compute(trades, oldPrice = 100)
        assertEquals(5, r.tradesExcluded)
        assertEquals(1, r.tradesUsed)
        assertEquals(100.0, r.vwap!!, 1e-9)
    }

    @Test
    fun pressureIgnoresFarAwayAndYoungOrders() {
        val cfg = config.copy(minOrderAgeMinutes = 10)
        val depth =
            listOf(
                DepthOrder(Side.BUY, 100, 1_000_000, now.minus(Duration.ofHours(1))),
                DepthOrder(Side.BUY, 200, 1_000_000_000, now.minus(Duration.ofHours(1))),
                DepthOrder(Side.BUY, 100, 1_000_000_000, now.minus(Duration.ofMinutes(1))),
                DepthOrder(Side.SELL, 100, 1_000_000, now.minus(Duration.ofHours(1))),
            )
        val r = compute(listOf(trade(100, 1)), oldPrice = 100, depth = depth, config = cfg)
        assertEquals(1_000_000, r.buyDepth)
        assertEquals(1_000_000, r.sellDepth)
        assertEquals(0.0, r.pressure, 1e-9)

        val oneSided = compute(listOf(trade(100, 1)), oldPrice = 100, depth = depth.take(1), config = cfg)
        assertEquals(cfg.pressureSensitivity, oneSided.pressure, 1e-9)
    }

    @Test
    fun confidenceRisesWithDistinctTradersNotJustTradeCount() {
        val pair = (1..40).map { trade(100, 1, buyer = 1, seller = 2) }
        val crowd = (1..40).map { trade(100, 1) }
        val few = compute(pair, oldPrice = 100).confidence
        val many = compute(crowd, oldPrice = 100).confidence
        assertTrue(many > few * 3, "40 trades between 40 accounts ($many) should far outweigh 40 between 2 ($few)")
    }

    @Test
    fun anchorIsStrongInThinMarketsAndWeakInDeepOnes() {
        val thin = compute(listOf(trade(200, 1)), oldPrice = 100, basePrice = 100)
        val deep = compute((1..60).map { trade(200, 5) }, oldPrice = 100, basePrice = 100)
        assertTrue(thin.anchorWeight > 0.8, "thin anchor ${thin.anchorWeight}")
        assertTrue(deep.anchorWeight < 0.05, "deep anchor ${deep.anchorWeight}")
        assertTrue(deep.newPrice > thin.newPrice)
    }

    @Test
    fun smoothingAndMaxChangeClampApply() {
        val cfg = config.copy(anchorMaxWeight = 0.0, maxChangeLowBps = 500, maxChangeHighBps = 500, smoothing = 1.0)
        val r = compute((1..60).map { trade(1000, 5) }, oldPrice = 100, config = cfg)
        assertTrue(r.clamped)
        assertEquals(105, r.newPrice)
    }

    @Test
    fun noTradesLeavesPriceUnchanged() {
        val r = compute(emptyList(), oldPrice = 137, basePrice = 150)
        assertEquals(137, r.newPrice)
        assertEquals(0.0, r.confidence)
        assertNull(r.vwap)
    }

    @Test
    fun noTradesCanDriftTowardAnchorWhenEnabled() {
        val r = compute(emptyList(), oldPrice = 100, basePrice = 150, config = config.copy(noTradeDriftBps = 100))
        assertEquals(101, r.newPrice)
    }

    @Test
    fun itemWithoutBasePriceStartsFromTradesInDiscovery() {
        val none = MarketPriceCalculator(config).compute(MarketPriceInputs(1, null, null, LaunchState.DISCOVERY, emptyList(), emptyList(), now))
        assertNull(none)
        val first = MarketPriceCalculator(config).compute(
            MarketPriceInputs(1, null, null, LaunchState.DISCOVERY, listOf(trade(300, 2), trade(100, 2)), emptyList(), now),
        )
        assertNotNull(first)
        assertEquals(200, first.oldPrice)
        assertEquals("INITIAL_BASE_PRICE", first.reason)
        assertEquals(LaunchState.DISCOVERY, first.launchState)
        assertEquals(config.discoveryMaxChangeBps / 10_000.0, first.maxChange, 1e-9)
    }

    @Test
    fun discoveryEndsOnceConfidencePasses() {
        val r = compute((1..60).map { trade(100, 5) }, oldPrice = 100, launchState = LaunchState.DISCOVERY)
        assertEquals(LaunchState.NORMAL, r.launchState)
    }

    @Test
    fun seededTradesAreWeakEvidenceAndNeverConfidence() {
        val seeded = (1..20).map { trade(120, 10, flags = listOf(PriceTrade.SEEDED_FLAG)) }
        val onlySeeded = compute(seeded, oldPrice = 100, basePrice = 100, config = config.copy(anchorMaxWeight = 0.0))
        assertEquals(0.0, onlySeeded.confidence)
        assertEquals(100, onlySeeded.newPrice)
        val withReal = compute(seeded + trade(100, 10), oldPrice = 100, config = config.copy(anchorMaxWeight = 0.0))
        assertTrue(withReal.vwap!! > 100 && withReal.vwap!! < 120, "seeds should nudge, not set, the vwap: ${withReal.vwap}")
        val stale = seeded.map { it.copy(executedAt = now.minus(Duration.ofDays(30))) }
        assertEquals(20, compute(stale + trade(100, 10), oldPrice = 100).tradesExcluded)
    }

    private fun trade(
        price: Long,
        quantity: Long,
        buyer: Int = nextAccount++,
        seller: Int = nextAccount++,
        source: OrderSource = OrderSource.PLAYER,
        flags: List<String> = emptyList(),
        ageHours: Double = 0.0,
    ): PriceTrade {
        val seeded = flags.contains(PriceTrade.SEEDED_FLAG)
        val at = now.minus(Duration.ofMillis((ageHours * 3_600_000).toLong()))
        return PriceTrade(if (seeded) null else buyer, if (seeded) null else seller, quantity, price, source, flags, at)
    }

    private fun compute(
        trades: List<PriceTrade>,
        oldPrice: Long,
        basePrice: Long? = null,
        depth: List<DepthOrder> = emptyList(),
        launchState: LaunchState = LaunchState.NORMAL,
        config: PricingConfig = this.config,
    ): MarketPriceResult =
        assertNotNull(MarketPriceCalculator(config).compute(MarketPriceInputs(1, oldPrice, basePrice, launchState, trades, depth, now)))
}
