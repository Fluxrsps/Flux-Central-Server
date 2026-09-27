package dev.or2.central.exchange.health

import dev.or2.central.exchange.ExchangeConfig
import dev.or2.central.exchange.ExchangeEngine
import dev.or2.central.exchange.ExchangeRepository
import dev.or2.central.exchange.model.CancelReason
import dev.or2.central.exchange.model.CreateOrderRequest
import dev.or2.central.exchange.model.CreateOrderResult
import dev.or2.central.exchange.model.OrderSource
import dev.or2.central.exchange.model.Side
import dev.or2.sql.OpenRuneSql
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

class SystemLiquidityService(
    private val dataSource: DataSource,
    private val engine: ExchangeEngine,
    private val config: () -> ExchangeConfig,
    private val alerts: AlertService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(SystemLiquidityService::class.java)
    private val repo = ExchangeRepository()

    class Summary(val items: Int, val placed: Int, val skipped: Int, val disabled: Int)

    fun run(): Summary {
        val cfg = config()
        if (!cfg.liquidity.enabled || cfg.liquidity.systemCharacterId <= 0) return Summary(0, 0, 0, 0)
        val now = clock.instant()
        val hour = now.truncatedTo(ChronoUnit.HOURS)
        val items = dataSource.connection.use { conn -> selectEnabled(conn) }
        var placed = 0
        var skipped = 0
        var disabled = 0
        for (item in items) {
            try {
                val liquidity = dataSource.connection.use { conn -> repo.findLiquidity(conn, item.objId) } ?: continue
                if (item.frozen || item.stablePrice == null) {
                    skipped++
                    continue
                }
                val share = dataSource.connection.use { conn -> playerShare(conn, item.objId, now) }
                if (windDown(item.objId, liquidity, share, cfg, now)) {
                    disabled++
                    continue
                }
                val scale = if (share != null && share >= 0.5) cfg.liquidity.windDownCapShareBps / 10_000.0 else 1.0
                val requestIds = mutableSetOf<String>()
                if (liquidity.buyEnabled) {
                    val price = item.stablePrice * (10_000 - liquidity.buySpreadBps) / 10_000
                    val ceiling = listOf(item.highAlch, item.shopSell).filter { it > 0 }.minOrNull()
                    if (price < 1 || (ceiling != null && price >= ceiling)) {
                        log.warn("system BUY for item {} skipped: price {} would not stay below alch/shop value {}", item.objId, price, ceiling)
                    } else {
                        requestIds += place(item.objId, Side.BUY, price, quantity(item.objId, Side.BUY, liquidity, scale, now), cfg, hour)
                    }
                }
                if (liquidity.sellEnabled) {
                    val price = maxOf(item.stablePrice * (10_000 + liquidity.sellSpreadBps) / 10_000, liquidity.sellFloor)
                    requestIds += place(item.objId, Side.SELL, price, quantity(item.objId, Side.SELL, liquidity, scale, now), cfg, hour)
                }
                placed += requestIds.count { it.isNotEmpty() }
                retireOthers(item.objId, requestIds)
            } catch (e: Exception) {
                skipped++
                log.warn("system liquidity failed for item {}", item.objId, e)
            }
        }
        alertNetGp(cfg, now)
        return Summary(items.size, placed, skipped, disabled)
    }

    private fun place(objId: Int, side: Side, price: Long, quantity: Long, cfg: ExchangeConfig, hour: Instant): String {
        if (quantity <= 0) return ""
        val requestId = "system-${side.name.lowercase()}-$objId-${hour.epochSecond}"
        val request =
            CreateOrderRequest(
                characterId = cfg.liquidity.systemCharacterId,
                objId = objId,
                side = side,
                quantity = quantity,
                limitPrice = price,
                clientRequestId = requestId,
                correlationId = UUID.nameUUIDFromBytes(requestId.toByteArray()),
                source = OrderSource.SYSTEM,
            )
        when (val created = engine.createOrder(request)) {
            is CreateOrderResult.Pending -> {
                engine.openOrder(created.order.id, request.characterId)
                engine.matchPass(created.order.id)
            }
            is CreateOrderResult.Existing -> Unit
            is CreateOrderResult.Rejected -> {
                log.warn("system {} for item {} rejected: {}", side, objId, created.reason)
                return ""
            }
        }
        return requestId
    }

    private fun quantity(objId: Int, side: Side, liquidity: ExchangeRepository.LiquidityRow, scale: Double, now: Instant): Long {
        val hourStart = now.truncatedTo(ChronoUnit.HOURS)
        val dayStart = now.truncatedTo(ChronoUnit.DAYS)
        val hourlyCap = ((if (side == Side.BUY) liquidity.hourlyCapBuy else liquidity.hourlyCapSell) * scale).toLong()
        val dailyCap = ((if (side == Side.BUY) liquidity.dailyCapBuy else liquidity.dailyCapSell) * scale).toLong()
        return dataSource.connection.use { c ->
            val (hourUsed, _) = repo.systemUsage(c, objId, side, 0, hourStart.minusMillis(1))
            val (dayUsed, _) = repo.systemUsage(c, objId, side, 0, dayStart.minusMillis(1))
            minOf(hourlyCap - hourUsed, dailyCap - dayUsed).coerceAtLeast(0)
        }
    }

    private fun retireOthers(objId: Int, keep: Set<String>) {
        val stale =
            dataSource.connection.use { conn ->
                conn.prepareStatement(OpenRuneSql.text("central/exchange/liquidity_select_system_orders.sql")).use { ps ->
                    ps.setInt(1, objId)
                    ps.executeQuery().use { rs ->
                        val out = mutableListOf<Long>()
                        while (rs.next()) if (rs.getString(2) !in keep) out += rs.getLong(1)
                        out
                    }
                }
            }
        for (id in stale) {
            engine.cancel(id, null, CancelReason.PLAYER, staffReason = "system liquidity replaced")
        }
    }

    private fun windDown(objId: Int, liquidity: ExchangeRepository.LiquidityRow, share: Double?, cfg: ExchangeConfig, now: Instant): Boolean {
        if (liquidity.pinned || share == null) return false
        val dayStart = now.truncatedTo(ChronoUnit.DAYS)
        val alreadyCounted =
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT updated_at FROM exchange_system_liquidity WHERE obj_id = ?").use { ps ->
                    ps.setInt(1, objId)
                    ps.executeQuery().use { rs -> rs.next() && !rs.getObject(1, OffsetDateTime::class.java).toInstant().isBefore(dayStart) }
                }
            }
        if (alreadyCounted) return false
        val above = share * 10_000 >= liquidity.playerVolumeThresholdBps
        val streak = if (above) liquidity.consecutiveDaysAbove + 1 else 0
        val disable = streak >= liquidity.windDownDays
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/exchange/liquidity_update_wind_down.sql")).use { ps ->
                ps.setInt(1, streak)
                ps.setBoolean(2, !disable)
                ps.setObject(3, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                ps.setInt(4, objId)
                ps.executeUpdate()
            }
            if (disable) {
                repo.insertEvent(
                    conn,
                    ExchangeRepository.EventRow(
                        type = "ADMIN_ACTION", objId = objId, correlationId = UUID.randomUUID(),
                        reason = "system liquidity wound down after $streak days above player volume threshold",
                        metadata = """{"action":"SYSTEM_LIQUIDITY_DISABLED","streak":$streak}""",
                    ),
                )
            }
        }
        if (disable) {
            retireOthers(objId, emptySet())
            if (config().ops.verboseLogging) {
                log.info("system liquidity for item {} wound down after {} days", objId, streak)
            }
        }
        return disable
    }

    private fun playerShare(conn: Connection, objId: Int, now: Instant): Double? =
        conn.prepareStatement(OpenRuneSql.text("central/exchange/liquidity_player_share.sql")).use { ps ->
            ps.setInt(1, objId)
            ps.setObject(2, OffsetDateTime.ofInstant(now.minus(Duration.ofHours(24)), ZoneOffset.UTC))
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val player = rs.getLong(1)
                val system = rs.getLong(2)
                if (player + system == 0L) null else player.toDouble() / (player + system)
            }
        }

    private fun alertNetGp(cfg: ExchangeConfig, now: Instant) {
        val dayStart = now.truncatedTo(ChronoUnit.DAYS)
        val (paid, taken) =
            dataSource.connection.use { conn ->
                val (_, paidGp) = repo.systemUsageGlobal(conn, Side.BUY, dayStart.minusMillis(1))
                val (_, takenGp) = repo.systemUsageGlobal(conn, Side.SELL, dayStart.minusMillis(1))
                paidGp to takenGp
            }
        val net = paid - taken
        if (cfg.health.systemNetGpAlert > 0 && net > cfg.health.systemNetGpAlert) {
            alerts.raise("SYSTEM_NET_GP", 0, AlertSeverity.WARN, """{"paid_out":$paid,"taken_in":$taken,"net":$net,"day":"$dayStart"}""")
        }
    }

    private class EnabledItem(val objId: Int, val stablePrice: Long?, val highAlch: Long, val shopSell: Long, val frozen: Boolean)

    private fun selectEnabled(conn: Connection): List<EnabledItem> =
        conn.prepareStatement(OpenRuneSql.text("central/exchange/liquidity_select_enabled.sql")).use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<EnabledItem>()
                while (rs.next()) {
                    out += EnabledItem(rs.getInt(1), rs.getLong(2).takeUnless { rs.wasNull() }, rs.getLong(3), rs.getLong(4), rs.getBoolean(5))
                }
                out
            }
        }
}
