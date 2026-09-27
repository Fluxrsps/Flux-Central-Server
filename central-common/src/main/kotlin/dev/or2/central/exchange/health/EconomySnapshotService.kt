package dev.or2.central.exchange.health

import dev.or2.sql.OpenRuneSql
import java.sql.Date
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

/**
 * The daily macro picture, Parts H2 and P12: GP and items locked in the exchange, tax sunk,
 * system faucet and sink, traders and orders, and a volume-weighted price index of today's
 * traded items as an inflation indicator. Rewritten in place for the current day.
 */
class EconomySnapshotService(private val dataSource: DataSource, private val clock: Clock = Clock.systemUTC()) {
    fun snapshot(): LocalDate {
        val now = clock.instant()
        val dayStart = now.truncatedTo(ChronoUnit.DAYS)
        val day = LocalDate.ofInstant(dayStart, ZoneOffset.UTC)
        val start = OffsetDateTime.ofInstant(dayStart, ZoneOffset.UTC)
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/exchange/snapshot_upsert.sql")).use { ps ->
                ps.setDate(1, Date.valueOf(day))
                ps.setObject(2, start)
                ps.setObject(3, start)
                ps.setObject(4, start)
                ps.setObject(5, start)
                ps.setDate(6, Date.valueOf(day))
                ps.setObject(7, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                ps.executeUpdate()
            }
        }
        return day
    }
}
