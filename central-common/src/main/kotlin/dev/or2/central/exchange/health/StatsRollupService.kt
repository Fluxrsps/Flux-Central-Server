package dev.or2.central.exchange.health

import dev.or2.sql.OpenRuneSql
import java.sql.Date
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

class StatsRollupService(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
    private val batch: Int = 500,
) {
    fun run(): Int {
        val pending =
            dataSource.connection.use { conn ->
                conn.prepareStatement(OpenRuneSql.text("central/exchange/rollup_pending_days.sql")).use { ps ->
                    ps.setInt(1, batch)
                    ps.executeQuery().use { rs ->
                        val out = mutableListOf<Pair<Int, LocalDate>>()
                        while (rs.next()) out += rs.getInt(1) to rs.getDate(2).toLocalDate()
                        out
                    }
                }
            }
        for ((objId, day) in pending) rollup(objId, day)
        return pending.size
    }

    fun rollup(objId: Int, day: LocalDate) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/exchange/rollup_upsert_day.sql")).use { ps ->
                val d = Date.valueOf(day)
                ps.setInt(1, objId)
                ps.setDate(2, d)
                ps.setInt(3, objId)
                ps.setDate(4, d)
                ps.setInt(5, objId)
                ps.setDate(6, d)
                ps.setInt(7, objId)
                ps.setDate(8, d)
                ps.setObject(9, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                ps.setInt(10, objId)
                ps.setDate(11, d)
                ps.executeUpdate()
            }
        }
    }
}
