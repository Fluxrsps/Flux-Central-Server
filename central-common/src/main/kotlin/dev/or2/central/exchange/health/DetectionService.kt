package dev.or2.central.exchange.health

import dev.or2.central.exchange.ExchangeConfig
import dev.or2.sql.OpenRuneSql
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

class DetectionService(
    private val dataSource: DataSource,
    private val config: () -> ExchangeConfig,
    private val alerts: AlertService,
    private val clock: Clock = Clock.systemUTC(),
) {
    class Summary(val farFromMarket: Int, val repeatedPairing: Int, val washTrading: Int, val cornering: Int, val rapidCancel: Int) {
        val total: Int
            get() = farFromMarket + repeatedPairing + washTrading + cornering + rapidCancel
    }

    fun run(): Summary {
        val cfg = config().health
        val now = clock.instant()
        val windowStart = now.minus(Duration.ofHours(cfg.detectionWindowHours.toLong()))
        return dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val touched = linkedSetOf<Int>()
                val far = farFromMarket(conn, windowStart, cfg.farFromMarketBps, now, touched)
                val pairing = repeatedPairing(conn, windowStart, cfg.repeatedPairingMinTrades, cfg.repeatedPairingShareBps, now)
                val wash = washTrading(conn, windowStart, cfg.washMinCycleTrades, now, touched)
                val corner = cornering(conn, windowStart, cfg.corneringMinValue, cfg.corneringShareBps, now, touched)
                val rapid = rapidCancel(conn, now.minus(Duration.ofHours(1)), cfg.rapidCancelPerHour, now)
                for (objId in touched) {
                    alerts.raise(conn, "SUSPICIOUS_PATTERN", objId, AlertSeverity.WARN, """{"flags_in_window":"see exchange_flags"}""")
                }
                if (pairing + rapid > 0) {
                    alerts.raise(conn, "SUSPICIOUS_PATTERN", 0, AlertSeverity.WARN, """{"repeated_pairing":$pairing,"rapid_cancel":$rapid}""")
                }
                conn.commit()
                Summary(far, pairing, wash, corner, rapid)
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    private fun farFromMarket(conn: Connection, since: Instant, bps: Int, now: Instant, touched: MutableSet<Int>): Int {
        var count = 0
        conn.prepareStatement(sql("detect_far_from_market")).use { ps ->
            ps.setInstant(1, since)
            ps.setLong(2, bps.toLong())
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val objId = rs.getInt(4)
                    insertFlag(
                        conn, "PRICE_FAR_FROM_MARKET", rs.getLong(1), null, objId, rs.getInt(2), rs.getInt(3),
                        """{"unit_price":${rs.getLong(5)},"quantity":${rs.getLong(6)},"market_price":${rs.getLong(7)}}""", now,
                    )
                    touched += objId
                    count++
                }
            }
        }
        return count
    }

    private fun repeatedPairing(conn: Connection, since: Instant, minTrades: Int, shareBps: Int, now: Instant): Int {
        var count = 0
        conn.prepareStatement(sql("detect_repeated_pairing")).use { ps ->
            ps.setInstant(1, since)
            ps.setInt(2, minTrades)
            ps.setLong(3, shareBps.toLong())
            ps.setLong(4, shareBps.toLong())
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    insertFlag(
                        conn, "REPEATED_PAIRING", null, null, null, rs.getInt(1), rs.getInt(2),
                        """{"pair_trades":${rs.getLong(3)},"a_total":${rs.getLong(4)},"c_total":${rs.getLong(5)}}""", now,
                    )
                    count++
                }
            }
        }
        return count
    }

    private fun washTrading(conn: Connection, since: Instant, minTrades: Int, now: Instant, touched: MutableSet<Int>): Int {
        var count = 0
        conn.prepareStatement(sql("detect_wash_trading")).use { ps ->
            ps.setInstant(1, since)
            ps.setInt(2, minTrades)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val objId = rs.getInt(3)
                    insertFlag(conn, "WASH_TRADING", null, null, objId, rs.getInt(1), rs.getInt(2), """{"round_trips":${rs.getLong(4)}}""", now)
                    touched += objId
                    count++
                }
            }
        }
        return count
    }

    private fun cornering(conn: Connection, since: Instant, minValue: Long, shareBps: Int, now: Instant, touched: MutableSet<Int>): Int {
        var count = 0
        conn.prepareStatement(sql("detect_cornering")).use { ps ->
            ps.setInstant(1, since)
            ps.setLong(2, minValue)
            ps.setLong(3, shareBps.toLong())
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val objId = rs.getInt(1)
                    insertFlag(
                        conn, "CORNERING", null, null, objId, rs.getInt(2), null,
                        """{"basis":"${rs.getString(3)}","held":${rs.getLong(4)},"total":${rs.getLong(5)}}""", now,
                    )
                    touched += objId
                    count++
                }
            }
        }
        return count
    }

    private fun rapidCancel(conn: Connection, since: Instant, perHour: Int, now: Instant): Int {
        var count = 0
        conn.prepareStatement(sql("detect_rapid_cancel")).use { ps ->
            ps.setInstant(1, since)
            ps.setInt(2, perHour)
            ps.setInstant(3, since)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    insertFlag(conn, "RAPID_CANCEL_REPLACE", null, null, null, rs.getInt(1), null, """{"cancels_last_hour":${rs.getLong(2)}}""", now)
                    count++
                }
            }
        }
        return count
    }

    private fun insertFlag(
        conn: Connection,
        kind: String,
        tradeId: Long?,
        orderId: Long?,
        objId: Int?,
        characterId: Int?,
        counterpartyId: Int?,
        details: String,
        now: Instant,
    ) {
        conn.prepareStatement(sql("flag_insert")).use { ps ->
            ps.setString(1, kind)
            if (tradeId == null) ps.setNull(2, Types.BIGINT) else ps.setLong(2, tradeId)
            if (orderId == null) ps.setNull(3, Types.BIGINT) else ps.setLong(3, orderId)
            if (objId == null) ps.setNull(4, Types.INTEGER) else ps.setInt(4, objId)
            if (characterId == null) ps.setNull(5, Types.INTEGER) else ps.setInt(5, characterId)
            if (counterpartyId == null) ps.setNull(6, Types.INTEGER) else ps.setInt(6, counterpartyId)
            ps.setString(7, details)
            ps.setInstant(8, now)
            ps.executeUpdate()
        }
    }

    private fun sql(name: String) = OpenRuneSql.text("central/exchange/$name.sql")

    private fun PreparedStatement.setInstant(index: Int, value: Instant) {
        setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
    }
}
