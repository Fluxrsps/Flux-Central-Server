package dev.or2.central.exchange.health

import dev.or2.sql.OpenRuneSql
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import javax.sql.DataSource

class ReconciliationService(
    private val dataSource: DataSource,
    private val alerts: AlertService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ReconciliationService::class.java)

    data class Mismatch(val check: String, val objId: Int, val subject: Long, val expected: Long, val actual: Long)

    class Report(val mismatches: List<Mismatch>, val durationMs: Long) {
        val clean: Boolean
            get() = mismatches.isEmpty()
    }

    fun run(): Report {
        val started = System.nanoTime()
        val found = mutableListOf<Mismatch>()
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                found += query(conn, "recon_trades", "TRADE_VALUE") { rs -> Mismatch("TRADE_VALUE", 0, rs.getLong(1), rs.getLong(2), rs.getLong(3)) }
                found += query(conn, "recon_order_fills", "ORDER_FILLS") { rs -> Mismatch("ORDER_FILLS", rs.getInt(2), rs.getLong(1), rs.getLong(3), rs.getLong(4)) }
                found += query(conn, "recon_order_reservations", "ORDER_RESERVATION") { rs -> Mismatch("ORDER_RESERVATION", rs.getInt(2), rs.getLong(1), rs.getLong(4), rs.getLong(3)) }
                found += query(conn, "recon_gp_ledger", "GP_LEDGER") { rs -> Mismatch("GP_LEDGER", rs.getInt(1), 0, rs.getLong(2), rs.getLong(3)) }
                found += query(conn, "recon_items_ledger", "ITEMS_LEDGER") { rs -> Mismatch("ITEMS_LEDGER", rs.getInt(1), 0, rs.getLong(2), rs.getLong(3)) }
                found += query(conn, "recon_items_custody", "ITEMS_CUSTODY") { rs -> Mismatch("ITEMS_CUSTODY", rs.getInt(1), 0, rs.getLong(2), rs.getLong(3)) }
                found += query(conn, "recon_owed_items", "OWED_ITEMS") { rs -> Mismatch("OWED_ITEMS", rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }
                found += query(conn, "recon_owed_gp", "OWED_GP") { rs -> Mismatch("OWED_GP", 0, rs.getLong(1), rs.getLong(2), rs.getLong(3)) }
                conn.prepareStatement(OpenRuneSql.text("central/exchange/recon_gp_custody.sql")).use { ps ->
                    ps.executeQuery().use { rs ->
                        if (rs.next() && rs.getLong(1) != rs.getLong(2)) {
                            found += Mismatch("GP_CUSTODY", 0, 0, rs.getLong(1), rs.getLong(2))
                        }
                    }
                }
                for (m in found) {
                    log.error("RECONCILIATION MISMATCH {} item={} subject={} expected={} actual={}", m.check, m.objId, m.subject, m.expected, m.actual)
                    alerts.raise(
                        conn, "RECONCILIATION", m.objId, AlertSeverity.CRITICAL,
                        """{"check":"${m.check}","subject":${m.subject},"expected":${m.expected},"actual":${m.actual}}""",
                    )
                    if (m.objId != 0) {
                        alerts.freezeItem(conn, m.objId, "RECONCILIATION", "${m.check}: expected ${m.expected}, found ${m.actual}")
                    }
                }
                conn.commit()
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
        val duration = (System.nanoTime() - started) / 1_000_000
        return Report(found, duration)
    }

    private fun query(conn: Connection, name: String, check: String, map: (java.sql.ResultSet) -> Mismatch): List<Mismatch> =
        conn.prepareStatement(OpenRuneSql.text("central/exchange/$name.sql")).use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Mismatch>()
                while (rs.next()) out += map(rs)
                if (out.isNotEmpty()) log.error("reconciliation check {} found {} mismatches", check, out.size)
                out
            }
        }
}
