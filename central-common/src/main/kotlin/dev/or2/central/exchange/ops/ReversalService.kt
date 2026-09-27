package dev.or2.central.exchange.ops

import dev.or2.central.exchange.ExchangeRepository
import dev.or2.sql.OpenRuneSql
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/**
 * Compensating transactions, Part P5. Unwinds one fill without deleting history: the buyer's items
 * go back to the seller's collection box and the seller's net proceeds go back to the buyer's,
 * taken from each party's box as far as it holds them. Whatever a party no longer has is recorded
 * as a debt, never silently dropped. The trade keeps its row and gains a reversed_at stamp, which
 * removes it from price discovery; the hour's statistics are adjusted.
 */
class ReversalService(private val dataSource: DataSource, private val clock: Clock = Clock.systemUTC()) {
    private val repo = ExchangeRepository()

    data class Plan(
        val tradeId: Long,
        val objId: Int,
        val buyerCharacterId: Int,
        val sellerCharacterId: Int,
        val itemsToRecover: Long,
        val itemsAvailable: Long,
        val itemsShortfall: Long,
        val gpToRecover: Long,
        val gpAvailable: Long,
        val gpShortfall: Long,
        val alreadyReversed: Boolean,
    ) {
        fun toJson(): String =
            """{"trade_id":$tradeId,"obj_id":$objId,"buyer":$buyerCharacterId,"seller":$sellerCharacterId,""" +
                """"items_to_recover":$itemsToRecover,"items_available":$itemsAvailable,"items_shortfall":$itemsShortfall,""" +
                """"gp_to_recover":$gpToRecover,"gp_available":$gpAvailable,"gp_shortfall":$gpShortfall}"""
    }

    class Result(val reversalId: Long, val plan: Plan, val applied: Boolean)

    fun reverse(tradeId: Long, staffCharacterId: Int, reason: String, dryRun: Boolean): Result {
        require(reason.isNotBlank()) { "a reversal needs a reason" }
        val now = clock.instant()
        return dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val trade = lockTrade(conn, tradeId) ?: error("trade $tradeId not found")
                if (trade.source == "SEEDED") error("seeded trades have nothing to reverse")
                val itemsAvailable = repo.lockCollectionItems(conn, trade.buyer, trade.objId)
                val gpAvailable = repo.lockCollectionGp(conn, trade.seller)
                val plan =
                    Plan(
                        tradeId = trade.id,
                        objId = trade.objId,
                        buyerCharacterId = trade.buyer,
                        sellerCharacterId = trade.seller,
                        itemsToRecover = trade.quantity,
                        itemsAvailable = minOf(itemsAvailable, trade.quantity),
                        itemsShortfall = (trade.quantity - itemsAvailable).coerceAtLeast(0),
                        gpToRecover = trade.netValue,
                        gpAvailable = minOf(gpAvailable, trade.netValue),
                        gpShortfall = (trade.netValue - gpAvailable).coerceAtLeast(0),
                        alreadyReversed = trade.reversedAt != null,
                    )
                if (plan.alreadyReversed) {
                    conn.rollback()
                    error("trade $tradeId was already reversed at ${trade.reversedAt}")
                }
                val correlation = UUID.randomUUID()
                val reversalId = insertReversal(conn, plan, staffCharacterId, reason, dryRun, correlation, now)
                if (dryRun) {
                    conn.commit()
                    return@use Result(reversalId, plan, applied = false)
                }

                if (plan.itemsAvailable > 0) {
                    repo.debitCollectionItems(conn, trade.buyer, trade.objId, plan.itemsAvailable)
                    repo.creditCollectionItems(conn, trade.seller, trade.objId, plan.itemsAvailable)
                }
                if (plan.gpAvailable > 0) {
                    repo.debitCollectionGp(conn, trade.seller, plan.gpAvailable)
                    repo.creditCollectionGp(conn, trade.buyer, plan.gpAvailable)
                }
                if (plan.itemsShortfall > 0) insertDebt(conn, trade.buyer, reversalId, trade.objId, plan.itemsShortfall, 0, now)
                if (plan.gpShortfall > 0) insertDebt(conn, trade.seller, reversalId, null, 0, plan.gpShortfall, now)

                conn.prepareStatement(OpenRuneSql.text("central/exchange/trade_mark_reversed.sql")).use { ps ->
                    ps.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                    ps.setLong(2, trade.id)
                    check(ps.executeUpdate() == 1)
                }
                adjustStats(conn, trade)

                val meta = """{"reversal_id":$reversalId,"plan":${plan.toJson()}}"""
                repo.insertEvent(conn, ExchangeRepository.EventRow("TRADE_REVERSED", trade.buyer, trade.buyOrderId, trade.id, null, trade.objId, plan.itemsAvailable, plan.gpAvailable, correlation, staffCharacterId, reason, metadata = meta))
                repo.insertEvent(conn, ExchangeRepository.EventRow("TRADE_REVERSED", trade.seller, trade.sellOrderId, trade.id, null, trade.objId, plan.itemsAvailable, plan.gpAvailable, correlation, staffCharacterId, reason, metadata = meta))
                conn.commit()
                Result(reversalId, plan, applied = true)
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    private class Trade(
        val id: Long,
        val buyOrderId: Long,
        val sellOrderId: Long,
        val buyer: Int,
        val seller: Int,
        val objId: Int,
        val quantity: Long,
        val unitPrice: Long,
        val grossValue: Long,
        val tax: Long,
        val netValue: Long,
        val source: String,
        val executedAt: Instant,
        val reversedAt: Instant?,
    )

    private fun lockTrade(conn: Connection, id: Long): Trade? =
        conn.prepareStatement(OpenRuneSql.text("central/exchange/trade_lock.sql")).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                Trade(
                    id = rs.getLong(1), buyOrderId = rs.getLong(2), sellOrderId = rs.getLong(3), buyer = rs.getInt(4), seller = rs.getInt(5),
                    objId = rs.getInt(6), quantity = rs.getLong(7), unitPrice = rs.getLong(8), grossValue = rs.getLong(9), tax = rs.getLong(10),
                    netValue = rs.getLong(11), source = rs.getString(12),
                    executedAt = rs.getObject(13, OffsetDateTime::class.java).toInstant(),
                    reversedAt = rs.getObject(14, OffsetDateTime::class.java)?.toInstant(),
                )
            }
        }

    private fun insertReversal(conn: Connection, plan: Plan, staff: Int, reason: String, dryRun: Boolean, correlation: UUID, now: Instant): Long =
        conn.prepareStatement(OpenRuneSql.text("central/exchange/reversal_insert.sql")).use { ps ->
            ps.setLong(1, plan.tradeId)
            ps.setInt(2, staff)
            ps.setString(3, reason)
            ps.setBoolean(4, dryRun)
            ps.setObject(5, correlation)
            ps.setString(6, plan.toJson())
            ps.setString(7, """{"items":${plan.itemsShortfall},"gp":${plan.gpShortfall}}""")
            ps.setObject(8, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            if (dryRun) ps.setNull(9, Types.TIMESTAMP_WITH_TIMEZONE) else ps.setObject(9, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            ps.executeQuery().use { rs ->
                check(rs.next())
                rs.getLong(1)
            }
        }

    private fun insertDebt(conn: Connection, characterId: Int, reversalId: Long, objId: Int?, count: Long, gp: Long, now: Instant) {
        conn.prepareStatement(OpenRuneSql.text("central/exchange/debt_insert.sql")).use { ps ->
            ps.setInt(1, characterId)
            ps.setLong(2, reversalId)
            if (objId == null) ps.setNull(3, Types.INTEGER) else ps.setInt(3, objId)
            ps.setLong(4, count)
            ps.setLong(5, gp)
            ps.setObject(6, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            ps.executeUpdate()
        }
    }

    private fun adjustStats(conn: Connection, trade: Trade) {
        conn.prepareStatement(OpenRuneSql.text("central/exchange/stats_hourly_reverse.sql")).use { ps ->
            ps.setLong(1, trade.quantity)
            ps.setBigDecimal(2, BigDecimal.valueOf(trade.grossValue))
            ps.setLong(3, trade.quantity)
            ps.setString(4, trade.source)
            ps.setLong(5, trade.quantity)
            ps.setString(6, trade.source)
            ps.setLong(7, trade.quantity)
            ps.setLong(8, trade.tax)
            ps.setInt(9, trade.objId)
            ps.setObject(10, OffsetDateTime.ofInstant(trade.executedAt.truncatedTo(ChronoUnit.HOURS), ZoneOffset.UTC))
            ps.executeUpdate()
        }
    }
}
