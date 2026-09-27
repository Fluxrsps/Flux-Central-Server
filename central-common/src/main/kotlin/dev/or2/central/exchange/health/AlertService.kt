package dev.or2.central.exchange.health

import dev.or2.sql.OpenRuneSql
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

enum class AlertSeverity { INFO, WARN, CRITICAL }

data class ExchangeAlert(
    val id: Long,
    val kind: String,
    val severity: AlertSeverity,
    val objId: Int,
    val details: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
)

/** Where new alerts go besides the table. Never throws into the caller. */
fun interface AlertNotifier {
    fun notify(alert: ExchangeAlert)
}

/**
 * Staff alerts, Part H3. De-duplicated in the database (one open alert per kind and item), so a
 * detector can raise the same condition every run and staff hear about it once. Alerts never
 * change prices or orders.
 */
class AlertService(
    private val dataSource: DataSource,
    private val notifier: AlertNotifier = AlertNotifier { },
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(AlertService::class.java)

    /** Raises or refreshes an alert. Returns true when it is new. */
    fun raise(kind: String, objId: Int, severity: AlertSeverity, details: String): Boolean =
        dataSource.connection.use { conn -> raise(conn, kind, objId, severity, details) }

    fun raise(conn: Connection, kind: String, objId: Int, severity: AlertSeverity, details: String): Boolean {
        val now = clock.instant()
        val (id, inserted) =
            conn.prepareStatement(OpenRuneSql.text("central/exchange/alert_upsert.sql")).use { ps ->
                ps.setString(1, kind)
                ps.setString(2, severity.name)
                ps.setInt(3, objId)
                ps.setString(4, details)
                ps.setObject(5, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                ps.setObject(6, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "alert upsert returned no row" }
                    rs.getLong(1) to rs.getBoolean(2)
                }
            }
        if (inserted) {
            val alert = ExchangeAlert(id, kind, severity, objId, details, now, now)
            log.warn("exchange alert {} {} item={} {}", severity, kind, objId, details)
            runCatching { notifier.notify(alert) }.onFailure { log.warn("alert notifier failed", it) }
        }
        return inserted
    }

    fun resolve(id: Long, staffCharacterId: Int?): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/exchange/alert_resolve.sql")).use { ps ->
                if (staffCharacterId == null) ps.setNull(1, java.sql.Types.INTEGER) else ps.setInt(1, staffCharacterId)
                ps.setObject(2, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                ps.setLong(3, id)
                ps.executeUpdate() == 1
            }
        }

    fun listOpen(): List<ExchangeAlert> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/exchange/alert_select_open.sql")).use { ps ->
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ExchangeAlert>()
                    while (rs.next()) {
                        out +=
                            ExchangeAlert(
                                id = rs.getLong(1),
                                kind = rs.getString(2),
                                severity = AlertSeverity.valueOf(rs.getString(3)),
                                objId = rs.getInt(4),
                                details = rs.getString(5),
                                createdAt = rs.getObject(6, OffsetDateTime::class.java).toInstant(),
                                lastSeenAt = rs.getObject(7, OffsetDateTime::class.java).toInstant(),
                            )
                    }
                    out
                }
            }
        }

    /** Applies an ITEM freeze unless one is already active. Used by reconciliation on a mismatch. */
    fun freezeItem(conn: Connection, objId: Int, reasonCode: String, reason: String) {
        conn.prepareStatement(OpenRuneSql.text("central/exchange/freeze_insert_if_absent.sql")).use { ps ->
            ps.setString(1, "ITEM")
            ps.setLong(2, objId.toLong())
            ps.setString(3, reasonCode)
            ps.setString(4, reason)
            ps.setNull(5, java.sql.Types.INTEGER)
            ps.setObject(6, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            ps.executeUpdate()
        }
    }
}

