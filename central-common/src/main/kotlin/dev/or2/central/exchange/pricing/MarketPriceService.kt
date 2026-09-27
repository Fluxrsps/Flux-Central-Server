package dev.or2.central.exchange.pricing

import dev.or2.central.exchange.ExchangeConfig
import dev.or2.central.exchange.ExchangeRepository
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

class MarketPriceService(
    private val dataSource: DataSource,
    private val config: () -> ExchangeConfig = { ExchangeConfig.DEFAULT },
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(MarketPriceService::class.java)
    private val repo = MarketPriceRepository()
    private val events = ExchangeRepository()

    class Summary(val processed: Int, val changed: Int, val skipped: Int, val failed: Int)

    fun recomputeAll(): Summary {
        val cfg = config()
        val calculator = MarketPriceCalculator(cfg.pricing)
        val items = dataSource.connection.use { conn -> repo.selectItems(conn) }
        var changed = 0
        var skipped = 0
        var failed = 0
        for (item in items) {
            try {
                when (recompute(item, calculator, cfg)) {
                    null -> skipped++
                    true -> changed++
                    false -> Unit
                }
            } catch (e: Exception) {
                failed++
                log.warn("market price recompute failed for item {}", item.objId, e)
            }
        }
        return Summary(items.size, changed, skipped, failed)
    }

    fun recompute(objId: Int): MarketPriceResult? {
        val cfg = config()
        val item = dataSource.connection.use { conn -> repo.selectItems(conn).firstOrNull { it.objId == objId } } ?: return null
        var result: MarketPriceResult? = null
        recompute(item, MarketPriceCalculator(cfg.pricing), cfg) { result = it }
        return result
    }

    private fun recompute(
        item: MarketPriceRepository.PricedItem,
        calculator: MarketPriceCalculator,
        cfg: ExchangeConfig,
        onResult: (MarketPriceResult) -> Unit = {},
    ): Boolean? {
        val now = clock.instant()
        val pricing = cfg.pricing
        return dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val reference = item.price ?: item.basePrice
                val trades = repo.selectTrades(conn, item.objId, now.minus(Duration.ofHours(pricing.tradeWindowHours.toLong())))
                val depth =
                    if (reference == null) emptyList()
                    else {
                        val band = reference * pricing.depthBandBps / 10_000.0
                        repo.selectDepth(conn, item.objId, (reference - band).toLong().coerceAtLeast(1), (reference + band).toLong() + 1)
                    }
                val inputs = MarketPriceInputs(item.objId, item.price, item.basePrice, item.launchState, trades, depth, now)
                val result = calculator.compute(inputs)
                if (result == null) {
                    conn.rollback()
                    return@use null
                }
                store(conn, item, result, cfg, now)
                conn.commit()
                onResult(result)
                result.changed
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    private fun store(conn: Connection, item: MarketPriceRepository.PricedItem, result: MarketPriceResult, cfg: ExchangeConfig, now: java.time.Instant) {
        repo.insertHistory(conn, result, now)
        val recent = repo.selectRecentPrices(conn, item.objId, now.minus(Duration.ofHours(cfg.pricing.stableWindowHours.toLong())))
        val stable = median(recent) ?: result.newPrice
        repo.upsertPrice(conn, item.objId, result.newPrice, stable, result.confidence, now)
        if (result.launchState != item.launchState) {
            repo.updateLaunchState(conn, item.objId, result.launchState)
        }
        if (result.changed) {
            events.insertEvent(
                conn,
                ExchangeRepository.EventRow(
                    type = "MARKET_PRICE_UPDATED",
                    objId = item.objId,
                    amount = result.newPrice,
                    correlationId = UUID.randomUUID(),
                    metadata = """{"old_price":${result.oldPrice},"confidence":${String.format(java.util.Locale.ROOT, "%.5f", result.confidence)},"reason":"${result.reason}"}""",
                ),
            )
        }
    }

    private fun median(sorted: List<Long>): Long? {
        if (sorted.isEmpty()) return null
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid] + 1) / 2
    }
}
