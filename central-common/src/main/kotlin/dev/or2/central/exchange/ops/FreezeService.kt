package dev.or2.central.exchange.ops

import dev.or2.central.exchange.ExchangeEngine
import dev.or2.central.exchange.ExchangeRepository
import dev.or2.central.exchange.model.CancelReason
import dev.or2.central.exchange.model.FreezeScope
import dev.or2.sql.OpenRuneSql
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * Kill switches, Part P4. A freeze takes effect on the next request because every create, match
 * and claim path reads the table. Global and item freezes leave cancels and claims working so
 * nobody's assets are trapped; an account freeze blocks claims too, pending investigation.
 */
class FreezeService(
    private val dataSource: DataSource,
    private val engine: ExchangeEngine,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val repo = ExchangeRepository()

    data class ActiveFreeze(
        val id: Long,
        val scope: FreezeScope,
        val targetId: Long,
        val reasonCode: String,
        val reason: String,
        val holdOrders: Boolean,
        val staffCharacterId: Int?,
        val createdAt: Instant,
    )

    /** Returns false when the target was already frozen. With [holdOrders] false, live orders are cancelled and refunded. */
    fun apply(scope: FreezeScope, targetId: Long, reasonCode: String, reason: String, staffCharacterId: Int?, holdOrders: Boolean = true): Boolean {
        require(reason.isNotBlank()) { "a freeze needs a reason" }
        require((scope == FreezeScope.GLOBAL) == (targetId == 0L)) { "GLOBAL takes target 0; ITEM and ACCOUNT need a target" }
        val now = clock.instant()
        val inserted =
            dataSource.connection.use { conn ->
                val rows =
                    conn.prepareStatement(OpenRuneSql.text("central/exchange/freeze_insert_if_absent.sql")).use { ps ->
                        ps.setString(1, scope.name)
                        ps.setLong(2, targetId)
                        ps.setString(3, reasonCode)
                        ps.setString(4, reason)
                        if (staffCharacterId == null) ps.setNull(5, Types.INTEGER) else ps.setInt(5, staffCharacterId)
                        ps.setObject(6, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        ps.executeUpdate()
                    }
                if (rows == 1) {
                    conn.prepareStatement("UPDATE exchange_freezes SET hold_orders = ? WHERE scope = ? AND target_id = ? AND lifted_at IS NULL").use { ps ->
                        ps.setBoolean(1, holdOrders)
                        ps.setString(2, scope.name)
                        ps.setLong(3, targetId)
                        ps.executeUpdate()
                    }
                    repo.insertEvent(conn, staffEvent("FREEZE_APPLIED", scope, targetId, staffCharacterId, "$reasonCode: $reason", """{"hold_orders":$holdOrders}"""))
                }
                rows == 1
            }
        if (inserted && !holdOrders) {
            when (scope) {
                FreezeScope.ACCOUNT -> engine.cancelAllLive(targetId.toInt(), CancelReason.FROZEN, staffCharacterId, reason)
                FreezeScope.ITEM -> engine.cancelAllForItem(targetId.toInt(), CancelReason.FROZEN, staffCharacterId, reason)
                FreezeScope.GLOBAL -> Unit
            }
        }
        return inserted
    }

    fun lift(scope: FreezeScope, targetId: Long, staffCharacterId: Int?, reason: String): Boolean {
        require(reason.isNotBlank()) { "lifting a freeze needs a reason" }
        val now = clock.instant()
        return dataSource.connection.use { conn ->
            val rows =
                conn.prepareStatement(OpenRuneSql.text("central/exchange/freeze_lift.sql")).use { ps ->
                    ps.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                    if (staffCharacterId == null) ps.setNull(2, Types.INTEGER) else ps.setInt(2, staffCharacterId)
                    ps.setString(3, scope.name)
                    ps.setLong(4, targetId)
                    ps.executeUpdate()
                }
            if (rows == 1) {
                repo.insertEvent(conn, staffEvent("FREEZE_LIFTED", scope, targetId, staffCharacterId, reason, "{}"))
            }
            rows == 1
        }
    }

    fun active(): List<ActiveFreeze> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/exchange/freeze_select_active.sql")).use { ps ->
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ActiveFreeze>()
                    while (rs.next()) {
                        out +=
                            ActiveFreeze(
                                id = rs.getLong(1),
                                scope = FreezeScope.valueOf(rs.getString(2)),
                                targetId = rs.getLong(3),
                                reasonCode = rs.getString(4),
                                reason = rs.getString(5),
                                holdOrders = rs.getBoolean(6),
                                staffCharacterId = rs.getInt(7).takeUnless { rs.wasNull() },
                                createdAt = rs.getObject(8, OffsetDateTime::class.java).toInstant(),
                            )
                    }
                    out
                }
            }
        }

    private fun staffEvent(type: String, scope: FreezeScope, targetId: Long, staff: Int?, reason: String, metadata: String) =
        ExchangeRepository.EventRow(
            type = type,
            characterId = if (scope == FreezeScope.ACCOUNT) targetId.toInt() else null,
            objId = if (scope == FreezeScope.ITEM) targetId.toInt() else null,
            correlationId = UUID.randomUUID(),
            staffCharacterId = staff,
            reason = reason,
            metadata = metadata,
        )
}
