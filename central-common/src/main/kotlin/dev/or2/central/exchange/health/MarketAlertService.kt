package dev.or2.central.exchange.health

import dev.or2.central.exchange.ExchangeConfig
import dev.or2.central.exchange.HealthConfig
import dev.or2.sql.OpenRuneSql
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.math.abs

class MarketAlertService(
    private val dataSource: DataSource,
    private val config: () -> ExchangeConfig,
    private val alerts: AlertService,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun run(): Int {
        val cfg = config().health
        val now = clock.instant()
        val dayAgo = OffsetDateTime.ofInstant(now.minus(Duration.ofHours(24)), ZoneOffset.UTC)
        val twoDaysAgo = OffsetDateTime.ofInstant(now.minus(Duration.ofHours(48)), ZoneOffset.UTC)
        val weekAgo = OffsetDateTime.ofInstant(now.minus(Duration.ofDays(7)), ZoneOffset.UTC)
        var raised = 0
        dataSource.connection.use { conn ->
            raised += perItem(conn, cfg, dayAgo, twoDaysAgo, weekAgo)
            raised += exchangeWide(conn, cfg, dayAgo, twoDaysAgo)
        }
        return raised
    }

    private fun perItem(conn: Connection, cfg: HealthConfig, dayAgo: OffsetDateTime, twoDaysAgo: OffsetDateTime, weekAgo: OffsetDateTime): Int {
        var raised = 0
        conn.prepareStatement(OpenRuneSql.text("central/exchange/alert_price_changes.sql")).use { ps ->
            ps.setObject(1, dayAgo)
            ps.setObject(2, weekAgo)
            ps.setObject(3, dayAgo)
            ps.setObject(4, twoDaysAgo)
            ps.setObject(5, dayAgo)
            ps.setObject(6, dayAgo)
            ps.setObject(7, twoDaysAgo)
            ps.setObject(8, dayAgo)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val objId = rs.getInt(1)
                    val price = rs.getLong(2)
                    val price24h = rs.getLong(3).takeUnless { rs.wasNull() }
                    val price7d = rs.getLong(4).takeUnless { rs.wasNull() }
                    val volume24h = rs.getLong(5)
                    val volumePrev = rs.getLong(6)
                    val traders = rs.getInt(7)
                    val tradersPrev = rs.getInt(8)
                    if (volume24h < cfg.alertMinVolume || traders < cfg.alertMinDistinctTraders) continue
                    val context = """"volume_24h":$volume24h,"traders_24h":$traders"""
                    if (price24h != null && changeBps(price24h, price) >= cfg.priceChange24hBps) {
                        if (alerts.raise(conn, "PRICE_CHANGE_24H", objId, AlertSeverity.WARN, """{"from":$price24h,"to":$price,$context}""")) raised++
                    }
                    if (price7d != null && changeBps(price7d, price) >= cfg.priceChange7dBps) {
                        if (alerts.raise(conn, "PRICE_CHANGE_7D", objId, AlertSeverity.WARN, """{"from":$price7d,"to":$price,$context}""")) raised++
                    }
                    if (jumped(volumePrev, volume24h, cfg.volumeChangeMultiple)) {
                        if (alerts.raise(conn, "VOLUME_CHANGE", objId, AlertSeverity.INFO, """{"previous_24h":$volumePrev,"last_24h":$volume24h,"traders_24h":$traders}""")) raised++
                    }
                    if (jumped(tradersPrev.toLong(), traders.toLong(), cfg.tradersChangeMultiple)) {
                        if (alerts.raise(conn, "TRADERS_CHANGE", objId, AlertSeverity.WARN, """{"previous_24h":$tradersPrev,"last_24h":$traders,"volume_24h":$volume24h}""")) raised++
                    }
                }
            }
        }
        return raised
    }

    private fun exchangeWide(conn: Connection, cfg: HealthConfig, dayAgo: OffsetDateTime, twoDaysAgo: OffsetDateTime): Int {
        var raised = 0
        conn.prepareStatement(OpenRuneSql.text("central/exchange/alert_exchange_wide.sql")).use { ps ->
            ps.setObject(1, dayAgo)
            ps.setObject(2, twoDaysAgo)
            ps.setObject(3, dayAgo)
            ps.setObject(4, dayAgo)
            ps.setObject(5, twoDaysAgo)
            ps.setObject(6, dayAgo)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return 0
                val volume = rs.getLong(1)
                val volumePrev = rs.getLong(2)
                val traders = rs.getLong(3)
                val tradersPrev = rs.getLong(4)
                if (traders < cfg.exchangeWideMinTraders) return 0
                if (jumped(volumePrev, volume, cfg.exchangeWideVolumeChangeMultiple)) {
                    if (alerts.raise(conn, "VOLUME_CHANGE", 0, AlertSeverity.WARN, """{"scope":"exchange","previous_24h":$volumePrev,"last_24h":$volume,"traders_24h":$traders}""")) raised++
                }
                if (jumped(tradersPrev, traders, cfg.exchangeWideTradersChangeMultiple)) {
                    if (alerts.raise(conn, "TRADERS_CHANGE", 0, AlertSeverity.WARN, """{"scope":"exchange","previous_24h":$tradersPrev,"last_24h":$traders,"volume_24h":$volume}""")) raised++
                }
            }
        }
        return raised
    }

    private fun jumped(previous: Long, current: Long, multiple: Double): Boolean =
        previous > 0 && current.toDouble() / previous >= multiple

    private fun changeBps(from: Long, to: Long): Long = if (from <= 0) 0 else abs(to - from) * 10_000 / from
}
