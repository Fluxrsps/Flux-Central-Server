package dev.or2.central.exchange

import dev.or2.central.exchange.model.CancelReason
import dev.or2.central.exchange.model.ClaimStatus
import dev.or2.central.exchange.model.CollectionBox
import dev.or2.central.exchange.model.CreateOrderRequest
import dev.or2.central.exchange.model.ExchangeClaim
import dev.or2.central.exchange.model.ExchangeItem
import dev.or2.central.exchange.model.ExchangeNotification
import dev.or2.central.exchange.model.ExchangeOrder
import dev.or2.central.exchange.model.PlayerOrderHistory
import dev.or2.central.exchange.model.FreezeScope
import dev.or2.central.exchange.model.OrderSource
import dev.or2.central.exchange.model.OrderStatus
import dev.or2.central.exchange.model.Side
import dev.or2.sql.OpenRuneSql
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Every statement the exchange runs, one method each, all on a caller-owned connection so the
 * engine can compose them inside a single transaction. No business rules live here.
 */
class ExchangeRepository {
    private val orderColumns = OpenRuneSql.text("central/exchange/order_columns.sql")
    private val claimColumns = OpenRuneSql.text("central/exchange/claim_columns.sql")

    private fun sql(name: String): String =
        OpenRuneSql.text("central/exchange/$name.sql", "{columns}" to orderColumns)

    private fun claimSql(name: String): String =
        OpenRuneSql.text("central/exchange/$name.sql", "{columns}" to claimColumns)

    fun findItem(conn: Connection, objId: Int): ExchangeItem? =
        conn.prepareStatement(sql("item_find")).use { ps ->
            ps.setInt(1, objId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                ExchangeItem(
                    objId = rs.getInt("obj_id"),
                    frozen = rs.getBoolean("frozen"),
                    basePrice = rs.getLong("base_price").takeUnless { rs.wasNull() },
                    buyLimit = rs.getInt("buy_limit").takeUnless { rs.wasNull() },
                    taxCategory = rs.getString("tax_category"),
                    taxExempt = rs.getBoolean("tax_exempt"),
                    launchBuyLimit = rs.getInt("launch_buy_limit").takeUnless { rs.wasNull() },
                    launchLimitUntil = rs.getObject("launch_limit_until", OffsetDateTime::class.java)?.toInstant(),
                )
            }
        }

    fun activeFreeze(conn: Connection, objId: Int, characterId: Int): FreezeScope? =
        conn.prepareStatement(sql("freeze_active")).use { ps ->
            ps.setLong(1, objId.toLong())
            ps.setLong(2, characterId.toLong())
            ps.executeQuery().use { rs -> if (rs.next()) FreezeScope.valueOf(rs.getString(1)) else null }
        }

    fun accountFrozen(conn: Connection, characterId: Int): Boolean =
        conn.prepareStatement(sql("freeze_account")).use { ps ->
            ps.setLong(1, characterId.toLong())
            ps.executeQuery().use { it.next() }
        }

    fun hasActiveBan(conn: Connection, characterId: Int): Boolean =
        conn.prepareStatement(sql("ban_active")).use { ps ->
            ps.setInt(1, characterId)
            ps.executeQuery().use { it.next() }
        }

    fun findOrder(conn: Connection, id: Long): ExchangeOrder? =
        conn.prepareStatement(sql("order_find")).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) readOrder(rs) else null }
        }

    fun lockOrder(conn: Connection, id: Long): ExchangeOrder? =
        conn.prepareStatement(sql("order_lock")).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) readOrder(rs) else null }
        }

    fun findOrderByRequest(conn: Connection, characterId: Int, clientRequestId: String): ExchangeOrder? =
        conn.prepareStatement(sql("order_find_by_request")).use { ps ->
            ps.setInt(1, characterId)
            ps.setString(2, clientRequestId)
            ps.executeQuery().use { rs -> if (rs.next()) readOrder(rs) else null }
        }

    fun insertPendingOrder(conn: Connection, request: CreateOrderRequest, now: Instant): ExchangeOrder =
        conn.prepareStatement(sql("order_insert_pending")).use { ps ->
            ps.setInt(1, request.characterId)
            ps.setInt(2, request.objId)
            ps.setString(3, request.side.name)
            ps.setString(4, request.source.name)
            ps.setLong(5, request.quantity)
            ps.setLong(6, request.limitPrice)
            ps.setString(7, request.clientRequestId)
            ps.setObject(8, request.correlationId)
            ps.setInt(9, request.world)
            ps.setNullableInt(10, request.createdBy)
            ps.setNullableInstant(11, request.expiresAt)
            ps.setInstant(12, now)
            ps.setInstant(13, now)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "insert returned no row" }
                readOrder(rs)
            }
        }

    fun countActiveOrders(conn: Connection, characterId: Int): Int =
        conn.prepareStatement(sql("order_count_active")).use { ps ->
            ps.setInt(1, characterId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    fun openOrder(conn: Connection, id: Long, reserved: Long): Boolean =
        conn.prepareStatement(sql("order_open")).use { ps ->
            ps.setLong(1, reserved)
            ps.setLong(2, id)
            ps.executeUpdate() == 1
        }

    fun applyFill(conn: Connection, id: Long, filled: Long, reserved: Long, status: OrderStatus) {
        conn.prepareStatement(sql("order_apply_fill")).use { ps ->
            ps.setLong(1, filled)
            ps.setLong(2, reserved)
            ps.setString(3, status.name)
            ps.setString(4, status.name)
            ps.setLong(5, id)
            check(ps.executeUpdate() == 1) { "fill did not update order $id" }
        }
    }

    fun terminateOrder(conn: Connection, id: Long, status: OrderStatus, reason: CancelReason?) {
        conn.prepareStatement(sql("order_terminate")).use { ps ->
            ps.setString(1, status.name)
            ps.setNullableString(2, reason?.name)
            ps.setLong(3, id)
            check(ps.executeUpdate() == 1) { "terminate did not update order $id" }
        }
    }

    fun selectCounterOrders(conn: Connection, incoming: ExchangeOrder, limit: Int): List<ExchangeOrder> {
        val name = if (incoming.side == Side.BUY) "order_select_counter_sell" else "order_select_counter_buy"
        return conn.prepareStatement(sql(name)).use { ps ->
            ps.setInt(1, incoming.objId)
            // Bound twice: once to exclude the character itself, once to exclude every other
            // character on the same account.
            ps.setInt(2, incoming.characterId)
            ps.setInt(3, incoming.characterId)
            ps.setLong(4, incoming.limitPrice)
            ps.setInt(5, limit)
            ps.executeQuery().use { rs -> readOrders(rs) }
        }
    }

    fun selectDueExpiry(conn: Connection, now: Instant, limit: Int): List<Long> =
        conn.prepareStatement(sql("order_select_due_expiry")).use { ps ->
            ps.setInstant(1, now)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> readIds(rs) }
        }

    fun selectStalePendingOrders(conn: Connection, characterId: Int, before: Instant): List<Long> =
        conn.prepareStatement(sql("order_select_stale_pending")).use { ps ->
            ps.setInt(1, characterId)
            ps.setInstant(2, before)
            ps.executeQuery().use { rs -> readIds(rs) }
        }

    fun selectLiveOrders(conn: Connection, characterId: Int): List<ExchangeOrder> =
        conn.prepareStatement(sql("order_select_live_by_character")).use { ps ->
            ps.setInt(1, characterId)
            ps.executeQuery().use { rs -> readOrders(rs) }
        }

    fun selectCrossableBuyOrders(conn: Connection, limit: Int): List<Long> =
        conn.prepareStatement(sql("order_select_crossable")).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs -> readIds(rs) }
        }

    class TradeRow(
        val buyOrderId: Long,
        val sellOrderId: Long,
        val buyerCharacterId: Int,
        val sellerCharacterId: Int,
        val makerSide: Side,
        val objId: Int,
        val quantity: Long,
        val unitPrice: Long,
        val grossValue: Long,
        val taxRateBps: Int,
        val tax: Long,
        val netValue: Long,
        val source: OrderSource,
        val flags: List<String>,
        val buyFilledBefore: Long,
        val correlationId: UUID,
        val world: Int,
    )

    fun insertTrade(conn: Connection, row: TradeRow): Pair<Long, Instant> =
        conn.prepareStatement(sql("trade_insert")).use { ps ->
            ps.setLong(1, row.buyOrderId)
            ps.setLong(2, row.sellOrderId)
            ps.setInt(3, row.buyerCharacterId)
            ps.setInt(4, row.sellerCharacterId)
            ps.setString(5, row.makerSide.name)
            ps.setInt(6, row.objId)
            ps.setLong(7, row.quantity)
            ps.setLong(8, row.unitPrice)
            ps.setLong(9, row.grossValue)
            ps.setInt(10, row.taxRateBps)
            ps.setLong(11, row.tax)
            ps.setLong(12, row.netValue)
            ps.setString(13, row.source.name)
            ps.setArray(14, conn.createArrayOf("text", row.flags.toTypedArray()))
            ps.setLong(15, row.buyFilledBefore)
            ps.setObject(16, row.correlationId)
            ps.setInt(17, row.world)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "trade insert returned no row" }
                rs.getLong(1) to rs.getInstant(2)
            }
        }

    fun creditCollectionItems(conn: Connection, characterId: Int, objId: Int, count: Long) {
        if (count <= 0) return
        conn.prepareStatement(sql("collection_credit_items")).use { ps ->
            ps.setInt(1, characterId)
            ps.setInt(2, objId)
            ps.setLong(3, count)
            ps.executeUpdate()
        }
    }

    fun creditCollectionGp(conn: Connection, characterId: Int, amount: Long) {
        if (amount <= 0) return
        conn.prepareStatement(sql("collection_credit_gp")).use { ps ->
            ps.setInt(1, characterId)
            ps.setLong(2, amount)
            ps.executeUpdate()
        }
    }

    fun lockCollectionItems(conn: Connection, characterId: Int, objId: Int): Long =
        conn.prepareStatement(sql("collection_items_lock")).use { ps ->
            ps.setInt(1, characterId)
            ps.setInt(2, objId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }

    fun lockCollectionGp(conn: Connection, characterId: Int): Long =
        conn.prepareStatement(sql("collection_gp_lock")).use { ps ->
            ps.setInt(1, characterId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }

    fun debitCollectionItems(conn: Connection, characterId: Int, objId: Int, count: Long) {
        val deleted =
            conn.prepareStatement(sql("collection_debit_items_exact")).use { ps ->
                ps.setInt(1, characterId)
                ps.setInt(2, objId)
                ps.setLong(3, count)
                ps.executeUpdate()
            }
        if (deleted == 1) return
        conn.prepareStatement(sql("collection_debit_items")).use { ps ->
            ps.setLong(1, count)
            ps.setInt(2, characterId)
            ps.setInt(3, objId)
            ps.setLong(4, count)
            check(ps.executeUpdate() == 1) { "collection items debit failed for $characterId/$objId" }
        }
    }

    fun debitCollectionGp(conn: Connection, characterId: Int, amount: Long) {
        val deleted =
            conn.prepareStatement(sql("collection_debit_gp_exact")).use { ps ->
                ps.setInt(1, characterId)
                ps.setLong(2, amount)
                ps.executeUpdate()
            }
        if (deleted == 1) return
        conn.prepareStatement(sql("collection_debit_gp")).use { ps ->
            ps.setLong(1, amount)
            ps.setInt(2, characterId)
            ps.setLong(3, amount)
            check(ps.executeUpdate() == 1) { "collection gp debit failed for $characterId" }
        }
    }

    fun readCollectionBox(conn: Connection, characterId: Int): CollectionBox {
        val items = linkedMapOf<Int, Long>()
        conn.prepareStatement(sql("collection_read_items")).use { ps ->
            ps.setInt(1, characterId)
            ps.executeQuery().use { rs -> while (rs.next()) items[rs.getInt(1)] = rs.getLong(2) }
        }
        val gp =
            conn.prepareStatement(sql("collection_read_gp")).use { ps ->
                ps.setInt(1, characterId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        return CollectionBox(items, gp)
    }

    fun insertClaim(
        conn: Connection,
        characterId: Int,
        objId: Int?,
        count: Long,
        gp: Long,
        clientRequestId: String,
        correlationId: UUID,
        world: Int,
        now: Instant,
    ): ExchangeClaim =
        conn.prepareStatement(claimSql("claim_insert")).use { ps ->
            ps.setInt(1, characterId)
            ps.setNullableInt(2, objId)
            ps.setLong(3, count)
            ps.setLong(4, gp)
            ps.setString(5, clientRequestId)
            ps.setObject(6, correlationId)
            ps.setInt(7, world)
            ps.setInstant(8, now)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "claim insert returned no row" }
                readClaim(rs)
            }
        }

    fun lockClaim(conn: Connection, id: Long): ExchangeClaim? =
        conn.prepareStatement(claimSql("claim_lock")).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) readClaim(rs) else null }
        }

    fun findClaimByRequest(conn: Connection, characterId: Int, clientRequestId: String): ExchangeClaim? =
        conn.prepareStatement(claimSql("claim_find_by_request")).use { ps ->
            ps.setInt(1, characterId)
            ps.setString(2, clientRequestId)
            ps.executeQuery().use { rs -> if (rs.next()) readClaim(rs) else null }
        }

    fun finishClaim(conn: Connection, id: Long, status: ClaimStatus) {
        conn.prepareStatement(claimSql("claim_finish")).use { ps ->
            ps.setString(1, status.name)
            ps.setLong(2, id)
            check(ps.executeUpdate() == 1) { "finish did not update claim $id" }
        }
    }

    fun selectStalePendingClaims(conn: Connection, characterId: Int, before: Instant): List<Long> =
        conn.prepareStatement(claimSql("claim_select_stale_pending")).use { ps ->
            ps.setInt(1, characterId)
            ps.setInstant(2, before)
            ps.executeQuery().use { rs -> readIds(rs) }
        }

    fun buyLimitUsed(conn: Connection, characterId: Int, objId: Int, since: Instant): Long =
        conn.prepareStatement(sql("buy_limit_used")).use { ps ->
            ps.setInt(1, characterId)
            ps.setInt(2, objId)
            ps.setInstant(3, since)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }

    fun addBuyLimitUsage(conn: Connection, characterId: Int, objId: Int, bucketStart: Instant, filled: Long) {
        conn.prepareStatement(sql("buy_limit_upsert")).use { ps ->
            ps.setInt(1, characterId)
            ps.setInt(2, objId)
            ps.setInstant(3, bucketStart)
            ps.setLong(4, filled)
            ps.executeUpdate()
        }
    }

    class EventRow(
        val type: String,
        val characterId: Int? = null,
        val orderId: Long? = null,
        val tradeId: Long? = null,
        val claimId: Long? = null,
        val objId: Int? = null,
        val quantity: Long? = null,
        val amount: Long? = null,
        val correlationId: UUID,
        val staffCharacterId: Int? = null,
        val reason: String? = null,
        val world: Int = 0,
        val metadata: String = "{}",
    )

    fun insertEvent(conn: Connection, row: EventRow) {
        conn.prepareStatement(sql("event_insert")).use { ps ->
            ps.setString(1, row.type)
            ps.setNullableInt(2, row.characterId)
            ps.setNullableLong(3, row.orderId)
            ps.setNullableLong(4, row.tradeId)
            ps.setNullableLong(5, row.claimId)
            ps.setNullableInt(6, row.objId)
            ps.setNullableLong(7, row.quantity)
            ps.setNullableLong(8, row.amount)
            ps.setObject(9, row.correlationId)
            ps.setNullableInt(10, row.staffCharacterId)
            ps.setNullableString(11, row.reason)
            ps.setInt(12, row.world)
            ps.setString(13, row.metadata)
            ps.executeUpdate()
        }
    }

    fun addHourlyStats(
        conn: Connection,
        objId: Int,
        bucketStart: Instant,
        unitPrice: Long,
        quantity: Long,
        grossValue: Long,
        source: OrderSource,
        tax: Long,
    ) {
        conn.prepareStatement(sql("stats_hourly_upsert")).use { ps ->
            ps.setInt(1, objId)
            ps.setInstant(2, bucketStart)
            ps.setLong(3, unitPrice)
            ps.setLong(4, unitPrice)
            ps.setLong(5, unitPrice)
            ps.setLong(6, unitPrice)
            ps.setBigDecimal(7, java.math.BigDecimal.valueOf(grossValue))
            ps.setLong(8, quantity)
            ps.setLong(9, quantity)
            ps.setLong(10, if (source == OrderSource.PLAYER) quantity else 0)
            ps.setLong(11, if (source == OrderSource.PLAYER) 0 else quantity)
            ps.setLong(12, tax)
            ps.executeUpdate()
        }
    }

    fun addHourlyTrader(conn: Connection, objId: Int, bucketStart: Instant, characterId: Int, side: Side, quantity: Long) {
        conn.prepareStatement(sql("stats_trader_upsert")).use { ps ->
            ps.setInt(1, objId)
            ps.setInstant(2, bucketStart)
            ps.setInt(3, characterId)
            ps.setString(4, side.name)
            ps.setLong(5, quantity)
            ps.executeUpdate()
        }
    }

    class LiquidityRow(
        val enabled: Boolean,
        val buyEnabled: Boolean,
        val sellEnabled: Boolean,
        val buySpreadBps: Int,
        val sellSpreadBps: Int,
        val sellFloor: Long,
        val hourlyCapBuy: Long,
        val dailyCapBuy: Long,
        val hourlyCapSell: Long,
        val dailyCapSell: Long,
        val perAccountDailyCap: Long,
        val windDownDays: Int,
        val playerVolumeThresholdBps: Int,
        val consecutiveDaysAbove: Int,
        val pinned: Boolean,
    )

    fun findLiquidity(conn: Connection, objId: Int): LiquidityRow? =
        conn.prepareStatement(sql("liquidity_find")).use { ps ->
            ps.setInt(1, objId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                LiquidityRow(
                    enabled = rs.getBoolean(1), buyEnabled = rs.getBoolean(2), sellEnabled = rs.getBoolean(3),
                    buySpreadBps = rs.getInt(4), sellSpreadBps = rs.getInt(5), sellFloor = rs.getLong(6),
                    hourlyCapBuy = rs.getLong(7), dailyCapBuy = rs.getLong(8), hourlyCapSell = rs.getLong(9),
                    dailyCapSell = rs.getLong(10), perAccountDailyCap = rs.getLong(11), windDownDays = rs.getInt(12),
                    playerVolumeThresholdBps = rs.getInt(13), consecutiveDaysAbove = rs.getInt(14), pinned = rs.getBoolean(15),
                )
            }
        }

    /** Filled quantity and GP against the system for one item, side and character (0 = item-wide) since [since]. */
    fun systemUsage(conn: Connection, objId: Int, side: Side, characterId: Int, since: Instant): Pair<Long, Long> =
        conn.prepareStatement(sql("system_usage_sum")).use { ps ->
            ps.setInt(1, objId)
            ps.setString(2, side.name)
            ps.setInt(3, characterId)
            ps.setInstant(4, since)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getLong(2) else 0L to 0L }
        }

    fun systemUsageGlobal(conn: Connection, side: Side, since: Instant): Pair<Long, Long> =
        conn.prepareStatement(sql("system_usage_global")).use { ps ->
            ps.setString(1, side.name)
            ps.setInstant(2, since)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getLong(2) else 0L to 0L }
        }

    fun addSystemUsage(conn: Connection, objId: Int, side: Side, bucketStart: Instant, characterId: Int, filled: Long, gp: Long) {
        conn.prepareStatement(sql("system_usage_upsert")).use { ps ->
            ps.setInt(1, objId)
            ps.setString(2, side.name)
            ps.setInstant(3, bucketStart)
            ps.setInt(4, characterId)
            ps.setLong(5, filled)
            ps.setLong(6, gp)
            ps.executeUpdate()
        }
    }

    fun selectUndeliveredNotifications(conn: Connection, characterId: Int, limit: Int): List<ExchangeNotification> =
        conn.prepareStatement(sql("notification_select_undelivered")).use { ps ->
            ps.setInt(1, characterId)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<ExchangeNotification>()
                while (rs.next()) {
                    out +=
                        ExchangeNotification(
                            id = rs.getLong(1),
                            characterId = characterId,
                            kind = rs.getString(2),
                            orderId = rs.getLong(3).takeUnless { rs.wasNull() },
                            objId = rs.getInt(4).takeUnless { rs.wasNull() },
                            payload = rs.getString(5),
                            deliveredAt = null,
                        )
                }
                out
            }
        }

    fun findNotification(conn: Connection, id: Long): ExchangeNotification? =
        conn.prepareStatement(sql("notification_find")).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                ExchangeNotification(
                    id = rs.getLong(1),
                    characterId = rs.getInt(2),
                    kind = rs.getString(3),
                    orderId = rs.getLong(4).takeUnless { rs.wasNull() },
                    objId = rs.getInt(5).takeUnless { rs.wasNull() },
                    payload = rs.getString(6),
                    deliveredAt = rs.getObject(7, OffsetDateTime::class.java)?.toInstant(),
                )
            }
        }

    fun markNotificationsDelivered(conn: Connection, ids: Collection<Long>, world: Int, now: Instant): Int {
        if (ids.isEmpty()) return 0
        return conn.prepareStatement(sql("notification_mark_delivered")).use { ps ->
            ps.setInstant(1, now)
            ps.setInt(2, world)
            ps.setArray(3, conn.createArrayOf("bigint", ids.toTypedArray()))
            ps.executeUpdate()
        }
    }

    fun playerHistory(conn: Connection, characterId: Int, limit: Int): List<PlayerOrderHistory> =
        conn.prepareStatement(sql("history_player")).use { ps ->
            ps.setInt(1, characterId)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<PlayerOrderHistory>()
                while (rs.next()) {
                    out +=
                        PlayerOrderHistory(
                            orderId = rs.getLong(1),
                            side = Side.valueOf(rs.getString(2)),
                            objId = rs.getInt(3),
                            quantity = rs.getLong(4),
                            filledQuantity = rs.getLong(5),
                            limitPrice = rs.getLong(6),
                            status = OrderStatus.valueOf(rs.getString(7)),
                            createdAt = rs.getInstant(8),
                            taxPaid = rs.getLong(9),
                            averagePrice = rs.getLong(10).takeUnless { rs.wasNull() },
                        )
                }
                out
            }
        }

    /** Adds to the per-character rejection counters and returns the new totals. */
    fun bumpAccountState(conn: Connection, characterId: Int, invalidInputs: Int, rateLimitTrips: Int, now: Instant): Pair<Int, Int> =
        conn.prepareStatement(sql("account_state_bump")).use { ps ->
            ps.setInt(1, characterId)
            ps.setInt(2, invalidInputs)
            ps.setInt(3, rateLimitTrips)
            ps.setInstant(4, now)
            ps.setInstant(5, now)
            ps.executeQuery().use { rs ->
                check(rs.next())
                rs.getInt(1) to rs.getInt(2)
            }
        }

    fun recentFlagExists(conn: Connection, kind: String, characterId: Int, since: Instant): Boolean =
        conn.prepareStatement(sql("flag_recent_exists")).use { ps ->
            ps.setString(1, kind)
            ps.setInt(2, characterId)
            ps.setInstant(3, since)
            ps.executeQuery().use { it.next() }
        }

    fun insertFlag(conn: Connection, kind: String, characterId: Int?, objId: Int?, tradeId: Long?, details: String, now: Instant) {
        conn.prepareStatement(sql("flag_insert")).use { ps ->
            ps.setString(1, kind)
            ps.setNullableLong(2, tradeId)
            ps.setNull(3, Types.BIGINT)
            ps.setNullableInt(4, objId)
            ps.setNullableInt(5, characterId)
            ps.setNull(6, Types.INTEGER)
            ps.setString(7, details)
            ps.setInstant(8, now)
            ps.executeUpdate()
        }
    }

    fun selectLiveOrderIdsByItem(conn: Connection, objId: Int): List<Long> =
        conn.prepareStatement(sql("order_select_live_by_item")).use { ps ->
            ps.setInt(1, objId)
            ps.executeQuery().use { rs -> readIds(rs) }
        }

    fun charactersOfAccount(conn: Connection, accountId: Long): List<Int> =
        conn.prepareStatement(sql("characters_of_account")).use { ps ->
            ps.setLong(1, accountId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Int>()
                while (rs.next()) out += rs.getInt(1)
                out
            }
        }

    fun insertNotification(conn: Connection, characterId: Int, kind: String, orderId: Long?, objId: Int?, payload: String) {
        conn.prepareStatement(sql("notification_insert")).use { ps ->
            ps.setInt(1, characterId)
            ps.setString(2, kind)
            ps.setNullableLong(3, orderId)
            ps.setNullableInt(4, objId)
            ps.setString(5, payload)
            ps.executeUpdate()
        }
    }

    private fun readOrders(rs: ResultSet): List<ExchangeOrder> {
        val out = mutableListOf<ExchangeOrder>()
        while (rs.next()) out += readOrder(rs)
        return out
    }

    private fun readIds(rs: ResultSet): List<Long> {
        val out = mutableListOf<Long>()
        while (rs.next()) out += rs.getLong(1)
        return out
    }

    private fun readOrder(rs: ResultSet): ExchangeOrder =
        ExchangeOrder(
            id = rs.getLong("id"),
            characterId = rs.getInt("character_id"),
            objId = rs.getInt("obj_id"),
            side = Side.valueOf(rs.getString("side")),
            source = OrderSource.valueOf(rs.getString("source")),
            quantity = rs.getLong("quantity"),
            filledQuantity = rs.getLong("filled_quantity"),
            limitPrice = rs.getLong("limit_price"),
            reservedAmount = rs.getLong("reserved_amount"),
            status = OrderStatus.valueOf(rs.getString("status")),
            cancelReason = rs.getString("cancel_reason")?.let(CancelReason::valueOf),
            clientRequestId = rs.getString("client_request_id"),
            correlationId = rs.getObject("correlation_id", UUID::class.java),
            world = rs.getInt("world"),
            createdAt = rs.getInstant("created_at"),
            expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java)?.toInstant(),
        )

    private fun readClaim(rs: ResultSet): ExchangeClaim =
        ExchangeClaim(
            id = rs.getLong("id"),
            characterId = rs.getInt("character_id"),
            status = ClaimStatus.valueOf(rs.getString("status")),
            objId = rs.getInt("obj_id").takeUnless { rs.wasNull() },
            count = rs.getLong("count"),
            gp = rs.getLong("gp"),
            clientRequestId = rs.getString("client_request_id"),
            correlationId = rs.getObject("correlation_id", UUID::class.java),
            createdAt = rs.getInstant("created_at"),
        )

    private fun ResultSet.getInstant(column: String): Instant =
        getObject(column, OffsetDateTime::class.java).toInstant()

    private fun ResultSet.getInstant(index: Int): Instant = getObject(index, OffsetDateTime::class.java).toInstant()

    private fun PreparedStatement.setInstant(index: Int, value: Instant) {
        setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
    }

    private fun PreparedStatement.setNullableInstant(index: Int, value: Instant?) {
        if (value == null) setNull(index, Types.TIMESTAMP_WITH_TIMEZONE) else setInstant(index, value)
    }

    private fun PreparedStatement.setNullableInt(index: Int, value: Int?) {
        if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
    }

    private fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
    }

    private fun PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }
}
