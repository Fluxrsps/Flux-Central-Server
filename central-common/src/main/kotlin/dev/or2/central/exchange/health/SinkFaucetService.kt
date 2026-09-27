package dev.or2.central.exchange.health

import dev.or2.central.exchange.ExchangeConfig
import dev.or2.central.exchange.model.Side
import dev.or2.central.exchange.ExchangeRepository
import dev.or2.sql.OpenRuneSql
import java.sql.Date
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

/**
 * Whole-economy GP faucets versus sinks for the current day, Part F9 and H3. Faucets are alchemy,
 * shop sell-backs, coin drops and what the system liquidity paid out; sinks are shop purchases,
 * coins destroyed or despawned, exchange tax, and what the system took in. Raises
 * SINK_FAUCET_IMBALANCE when faucets outrun sinks by the configured ratio above a minimum size.
 */
class SinkFaucetService(
    private val dataSource: DataSource,
    private val config: () -> ExchangeConfig,
    private val alerts: AlertService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val repo = ExchangeRepository()

    class Balance(
        val day: LocalDate,
        val alchemy: Long,
        val shopSell: Long,
        val coinDrops: Long,
        val systemPaid: Long,
        val shopBuy: Long,
        val coinsLost: Long,
        val exchangeTax: Long,
        val systemTaken: Long,
        val itemFaucets: Long,
        val itemSinks: Long,
    ) {
        val faucets: Long
            get() = alchemy + shopSell + coinDrops + systemPaid

        val sinks: Long
            get() = shopBuy + coinsLost + exchangeTax + systemTaken

        val ratio: Double
            get() = if (sinks <= 0) Double.POSITIVE_INFINITY else faucets.toDouble() / sinks
    }

    fun balance(): Balance {
        val now = clock.instant()
        val dayStart = now.truncatedTo(ChronoUnit.DAYS)
        val day = LocalDate.ofInstant(dayStart, ZoneOffset.UTC)
        return dataSource.connection.use { conn ->
            val flow =
                conn.prepareStatement(OpenRuneSql.text("central/exchange/flow_gp_day.sql")).use { ps ->
                    ps.setDate(1, Date.valueOf(day))
                    ps.executeQuery().use { rs ->
                        rs.next()
                        LongArray(7) { rs.getLong(it + 1) }
                    }
                }
            val tax =
                conn.prepareStatement("SELECT COALESCE(sum(tax), 0) FROM exchange_trades WHERE executed_at >= ?").use { ps ->
                    ps.setObject(1, java.time.OffsetDateTime.ofInstant(dayStart, ZoneOffset.UTC))
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                }
            val (_, paid) = repo.systemUsageGlobal(conn, Side.BUY, dayStart.minusMillis(1))
            val (_, taken) = repo.systemUsageGlobal(conn, Side.SELL, dayStart.minusMillis(1))
            Balance(day, flow[0], flow[1], flow[2], paid, flow[3], flow[4], tax, taken, flow[5], flow[6])
        }
    }

    fun run(): Boolean {
        val cfg = config().health
        val b = balance()
        if (b.faucets < cfg.sinkFaucetMinGp || b.ratio < cfg.sinkFaucetRatio) return false
        val ratio = if (b.ratio.isInfinite()) "\"inf\"" else String.format(java.util.Locale.ROOT, "%.2f", b.ratio)
        return alerts.raise(
            "SINK_FAUCET_IMBALANCE", 0, AlertSeverity.WARN,
            """{"day":"${b.day}","faucets":${b.faucets},"sinks":${b.sinks},"ratio":$ratio,"alchemy":${b.alchemy},"shop_sell":${b.shopSell},"coin_drops":${b.coinDrops},"system_paid":${b.systemPaid},"shop_buy":${b.shopBuy},"coins_lost":${b.coinsLost},"exchange_tax":${b.exchangeTax},"system_taken":${b.systemTaken}}""",
        )
    }
}
