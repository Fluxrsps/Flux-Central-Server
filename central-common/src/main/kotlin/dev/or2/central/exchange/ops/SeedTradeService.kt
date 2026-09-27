package dev.or2.central.exchange.ops

import dev.or2.central.exchange.ExchangeConfig
import dev.or2.central.exchange.ExchangeRepository
import dev.or2.sql.OpenRuneSql
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Fabricated launch trades, Part Q2.
 *
 * A brand new exchange has empty charts, which tells a player nothing and makes them reluctant to
 * be the first to offer anything. Seeded trades fill that gap, and everything about them is built
 * so they can never be confused with real trading or leave a mark that outlives them:
 *
 * - they reference no orders and no accounts, which the `exchange_trades` CHECK enforces;
 * - no GP or items move, so conservation is untouched and reconciliation has nothing to check;
 * - they carry `source = SEEDED`, so price discovery weights them weakly and fading, they never
 *   count toward confidence, and public statistics leave them out;
 * - they are the only rows in the table that may be deleted, and purging recomputes the buckets
 *   they touched from the trades that remain.
 *
 * Seeding is refused unless it has been turned on deliberately in config, and every run is an
 * audited staff action.
 */
class SeedTradeService(
    private val dataSource: DataSource,
    private val config: () -> ExchangeConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(SeedTradeService::class.java)
    private val repo = ExchangeRepository()

    class SeedRequest(
        val objId: Int,
        val trades: Int,
        /** Null uses the item's base price. */
        val centrePrice: Long? = null,
        val maxQuantity: Int? = null,
        val windowHours: Int? = null,
    )

    class SeedResult(val objId: Int, val created: Int, val quantity: Long, val skipped: String? = null) {
        val ok: Boolean
            get() = skipped == null
    }

    class PurgeResult(val trades: Int, val quantity: Long, val items: List<Int>, val dryRun: Boolean) {
        override fun toString(): String =
            "${if (dryRun) "would purge" else "purged"} $trades seeded trade(s), $quantity units, across ${items.size} item(s)"
    }

    /**
     * Creates [SeedRequest.trades] fabricated trades for each request, scattered across the seeding
     * window and around the item's base price. Returns one result per request; a request whose item
     * has no base price, or which would exceed a cap, is skipped with a reason rather than failing
     * the batch.
     */
    fun seed(requests: List<SeedRequest>, staffCharacterId: Int, reason: String, seed: Long = clock.millis()): List<SeedResult> {
        require(reason.isNotBlank()) { "seeding needs a reason" }
        val cfg = config()
        check(cfg.seeding.enabled) {
            "Launch seeding is disabled. Set seeding.enabled in exchange_config to allow fabricated trades."
        }
        val random = Random(seed)
        val now = clock.instant()
        val results = mutableListOf<SeedResult>()

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val existingTotal = countSeeded(conn, objId = 0).first
                var budget = cfg.seeding.maxTotalTrades - existingTotal
                val touched = linkedSetOf<Int>()

                for (request in requests) {
                    val item = repo.findItem(conn, request.objId)
                    val centre = request.centrePrice ?: item?.basePrice
                    when {
                        item == null -> results += SeedResult(request.objId, 0, 0, "item is not listed on the exchange")
                        centre == null || centre < 1 -> results += SeedResult(request.objId, 0, 0, "item has no base price to seed around")
                        request.trades < 1 -> results += SeedResult(request.objId, 0, 0, "nothing requested")
                        else -> {
                            val existingForItem = countSeeded(conn, request.objId).first
                            val perItemRoom = cfg.seeding.maxTradesPerItem - existingForItem
                            val allowed = minOf(request.trades.toLong(), perItemRoom.toLong(), budget.toLong()).toInt()
                            if (allowed < 1) {
                                results +=
                                    SeedResult(
                                        request.objId, 0, 0,
                                        if (perItemRoom < 1) "already at the per-item cap of ${cfg.seeding.maxTradesPerItem}"
                                        else "at the overall cap of ${cfg.seeding.maxTotalTrades} seeded trades",
                                    )
                            } else {
                                val quantity = insert(conn, request, centre, allowed, cfg, random, now)
                                budget -= allowed
                                touched += request.objId
                                results += SeedResult(request.objId, allowed, quantity)
                            }
                        }
                    }
                }

                for (objId in touched) {
                    rebuildBuckets(conn, objId)
                }
                if (touched.isNotEmpty()) {
                    val created = results.sumOf { it.created }
                    repo.insertEvent(
                        conn,
                        ExchangeRepository.EventRow(
                            type = "SEED_TRADES_CREATED", correlationId = UUID.randomUUID(),
                            staffCharacterId = staffCharacterId, reason = reason, quantity = created.toLong(),
                            metadata = """{"items":${touched.size},"trades":$created,"seed":$seed}""",
                        ),
                    )
                }
                conn.commit()
                log.info("seeded {} trade(s) across {} item(s)", results.sumOf { it.created }, touched.size)
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
        return results
    }

    /** Removes seeded trades for one item, or every item when [objId] is null, and rebuilds their buckets. */
    fun purge(objId: Int?, staffCharacterId: Int, reason: String, dryRun: Boolean): PurgeResult {
        require(reason.isNotBlank()) { "a purge needs a reason" }
        val scope = objId ?: 0
        return dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val (trades, quantity) = countSeeded(conn, scope)
                val items = affectedItems(conn, scope)
                if (dryRun) {
                    conn.rollback()
                    return@use PurgeResult(trades, quantity, items, dryRun = true)
                }
                conn.prepareStatement(OpenRuneSql.text("central/exchange/seed_trade_delete.sql")).use { ps ->
                    ps.setInt(1, scope)
                    ps.setInt(2, scope)
                    ps.executeUpdate()
                }
                for (item in items) {
                    rebuildBuckets(conn, item)
                }
                repo.insertEvent(
                    conn,
                    ExchangeRepository.EventRow(
                        type = "SEED_TRADES_PURGED", objId = objId, correlationId = UUID.randomUUID(),
                        staffCharacterId = staffCharacterId, reason = reason, quantity = trades.toLong(),
                        metadata = """{"items":${items.size},"trades":$trades,"quantity":$quantity}""",
                    ),
                )
                conn.commit()
                log.info("purged {} seeded trade(s) across {} item(s)", trades, items.size)
                PurgeResult(trades, quantity, items, dryRun = false)
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    fun seededCount(objId: Int? = null): Pair<Int, Long> =
        dataSource.connection.use { conn -> countSeeded(conn, objId ?: 0) }

    private fun insert(
        conn: Connection,
        request: SeedRequest,
        centre: Long,
        count: Int,
        cfg: ExchangeConfig,
        random: Random,
        now: Instant,
    ): Long {
        val variance = cfg.seeding.priceVarianceBps
        val maxQuantity = (request.maxQuantity ?: cfg.seeding.maxQuantityPerTrade).coerceAtLeast(1)
        val windowMinutes = (request.windowHours ?: cfg.seeding.windowHours).coerceAtLeast(1) * 60L
        var total = 0L
        conn.prepareStatement(OpenRuneSql.text("central/exchange/seed_trade_insert.sql")).use { ps ->
            repeat(count) {
                val drift = random.nextInt(-variance, variance + 1)
                val price = (centre * (10_000 + drift) / 10_000.0).roundToLong().coerceAtLeast(1)
                val quantity = random.nextInt(1, maxQuantity + 1).toLong()
                val gross = price * quantity
                val at = now.minus(Duration.ofMinutes(random.nextLong(0, windowMinutes)))
                ps.setInt(1, request.objId)
                ps.setLong(2, quantity)
                ps.setLong(3, price)
                ps.setLong(4, gross)
                ps.setLong(5, gross)
                ps.setObject(6, UUID.randomUUID())
                ps.setObject(7, OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                ps.addBatch()
                total += quantity
            }
            ps.executeBatch()
        }
        return total
    }

    private fun rebuildBuckets(conn: Connection, objId: Int) {
        conn.prepareStatement(OpenRuneSql.text("central/exchange/stats_hourly_rebuild.sql")).use { ps ->
            ps.setInt(1, objId)
            ps.executeUpdate()
        }
        conn.prepareStatement(OpenRuneSql.text("central/exchange/stats_hourly_drop_empty.sql")).use { ps ->
            ps.setInt(1, objId)
            ps.executeUpdate()
        }
    }

    private fun countSeeded(conn: Connection, objId: Int): Pair<Int, Long> =
        conn.prepareStatement(OpenRuneSql.text("central/exchange/seed_trade_count.sql")).use { ps ->
            ps.setInt(1, objId)
            ps.setInt(2, objId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) to rs.getLong(2) else 0 to 0L }
        }

    private fun affectedItems(conn: Connection, objId: Int): List<Int> =
        conn.prepareStatement(OpenRuneSql.text("central/exchange/seed_affected_items.sql")).use { ps ->
            ps.setInt(1, objId)
            ps.setInt(2, objId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Int>()
                while (rs.next()) out += rs.getInt(1)
                out
            }
        }
}
