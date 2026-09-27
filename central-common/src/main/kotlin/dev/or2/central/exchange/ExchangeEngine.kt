package dev.or2.central.exchange

import dev.or2.central.exchange.model.CancelReason
import dev.or2.central.exchange.model.CancelResult
import dev.or2.central.exchange.model.ClaimResult
import dev.or2.central.exchange.model.ClaimStatus
import dev.or2.central.exchange.model.CollectionBox
import dev.or2.central.exchange.model.CreateOrderRequest
import dev.or2.central.exchange.model.CreateOrderResult
import dev.or2.central.exchange.model.ExchangeClaim
import dev.or2.central.exchange.model.ExchangeItem
import dev.or2.central.exchange.model.ExchangeNotification
import dev.or2.central.exchange.model.ExchangeOrder
import dev.or2.central.exchange.model.PlayerOrderHistory
import dev.or2.central.exchange.model.Fill
import dev.or2.central.exchange.model.FreezeScope
import dev.or2.central.exchange.model.MatchPassResult
import dev.or2.central.exchange.model.OpenOrderResult
import dev.or2.central.exchange.model.OrderSource
import dev.or2.central.exchange.model.OrderStatus
import dev.or2.central.exchange.model.RecoveryAction
import dev.or2.central.exchange.model.RecoveryMarkers
import dev.or2.central.exchange.model.RejectReason
import dev.or2.central.exchange.model.Side
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/**
 * The Trading Post core: order lifecycle, matching, settlement, collection box and claims, all as
 * short PostgreSQL transactions on a shared database. Both the game worlds and Central drive this
 * class, so it holds no in-memory state beyond configuration.
 *
 * Locking model: an operation locks the order it was asked about with `FOR UPDATE`, then scans
 * the opposite book with `FOR UPDATE SKIP LOCKED`. Nothing ever waits on a lock another
 * transaction holds, so there is no deadlock ordering to maintain; a pair of orders that skip
 * each other is picked up by the next sweep.
 */
class ExchangeEngine(
    private val dataSource: DataSource,
    private val config: () -> ExchangeConfig = { ExchangeConfig.DEFAULT },
    private val pricePolicy: ExecutionPricePolicy = RestingOrderPricePolicy,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ExchangeEngine::class.java)
    private val repo = ExchangeRepository()

    fun createOrder(request: CreateOrderRequest): CreateOrderResult {
        val cfg = config()
        val quantity = request.quantity
        val limit = request.limitPrice
        if (quantity <= 0 || limit < cfg.minLimitPrice || request.clientRequestId.isBlank()) {
            return CreateOrderResult.Rejected(RejectReason.INVALID_INPUT)
        }
        if (quantity > cfg.maxQuantityPerOrder) {
            return CreateOrderResult.Rejected(RejectReason.MAX_QUANTITY)
        }
        val value = runCatching { Math.multiplyExact(quantity, limit) }.getOrNull()
        if (value == null || value > cfg.maxOrderValue) {
            return CreateOrderResult.Rejected(RejectReason.MAX_VALUE)
        }
        request.expiresAt?.let { if (!it.isAfter(clock.instant())) return CreateOrderResult.Rejected(RejectReason.INVALID_INPUT) }

        return try {
            transact { conn ->
                repo.findOrderByRequest(conn, request.characterId, request.clientRequestId)?.let {
                    return@transact CreateOrderResult.Existing(it)
                }
                val item =
                    repo.findItem(conn, request.objId)
                        ?: return@transact CreateOrderResult.Rejected(RejectReason.UNKNOWN_ITEM)
                if (item.frozen) {
                    return@transact CreateOrderResult.Rejected(RejectReason.ITEM_FROZEN)
                }
                repo.activeFreeze(conn, request.objId, request.characterId)?.let {
                    return@transact CreateOrderResult.Rejected(it.rejectReason())
                }
                if (request.source == OrderSource.PLAYER) {
                    if (repo.hasActiveBan(conn, request.characterId)) {
                        return@transact CreateOrderResult.Rejected(RejectReason.BANNED)
                    }
                    if (repo.countActiveOrders(conn, request.characterId) >= cfg.maxActiveOrders) {
                        return@transact CreateOrderResult.Rejected(RejectReason.SLOT_LIMIT)
                    }
                }
                val order = repo.insertPendingOrder(conn, request, clock.instant())
                repo.insertEvent(
                    conn,
                    ExchangeRepository.EventRow(
                        type = if (request.source == OrderSource.PLAYER) "ORDER_CREATED" else "SYSTEM_ORDER_CREATED",
                        characterId = order.characterId,
                        orderId = order.id,
                        objId = order.objId,
                        quantity = order.quantity,
                        amount = order.limitPrice,
                        correlationId = order.correlationId,
                        staffCharacterId = request.createdBy,
                        reason = request.createdBy?.let { "order created by staff/system" },
                        world = order.world,
                        metadata = """{"side":"${order.side}","source":"${order.source}"}""",
                    ),
                )
                ExchangeMetrics.ordersCreated.incrementAndGet()
                log.info(
                    "exchange order created order={} correlation={} character={} item={} side={} qty={} limit={} source={}",
                    order.id, order.correlationId, order.characterId, order.objId, order.side, order.quantity, order.limitPrice, order.source,
                )
                CreateOrderResult.Pending(order)
            }
        } catch (e: SQLException) {
            if (e.sqlState == UNIQUE_VIOLATION) {
                transact { conn -> repo.findOrderByRequest(conn, request.characterId, request.clientRequestId) }
                    ?.let { return CreateOrderResult.Existing(it) }
            }
            throw e
        }
    }

    /** Second step of creation: the world has taken the assets, so the order goes live and is matched. */
    fun openOrder(orderId: Long, characterId: Int): OpenOrderResult =
        transact { conn ->
            val order = repo.lockOrder(conn, orderId) ?: return@transact OpenOrderResult.Rejected(RejectReason.NOT_FOUND)
            if (order.characterId != characterId) {
                return@transact OpenOrderResult.Rejected(RejectReason.NOT_OWNER)
            }
            if (order.status != OrderStatus.PENDING) {
                return@transact OpenOrderResult.AlreadyOpened(order)
            }
            val reserved = order.expectedReservation(order.quantity)
            repo.openOrder(conn, order.id, reserved)
            repo.insertEvent(conn, event("ORDER_OPENED", order, amount = reserved))
            repo.insertEvent(
                conn,
                event(
                    if (order.side == Side.BUY) "BUY_FUNDS_RESERVED" else "SELL_ITEMS_RESERVED",
                    order,
                    quantity = order.quantity,
                    amount = reserved,
                ),
            )
            OpenOrderResult.Opened(order.copy(status = OrderStatus.OPEN, reservedAmount = reserved))
        }

    /**
     * One bounded pass of matching for [orderId]: up to `maxFillsPerTransaction` fills against
     * the best resting orders, all in one transaction. Returns the fills and the order as it
     * stands afterwards.
     */
    fun matchPass(orderId: Long): MatchPassResult {
        val started = System.nanoTime()
        try {
            return matchPassInner(orderId)
        } finally {
            ExchangeMetrics.recordMatchPass((System.nanoTime() - started) / 1_000_000)
        }
    }

    private fun matchPassInner(orderId: Long): MatchPassResult {
        val cfg = config()
        return transact { conn ->
            var incoming = repo.lockOrder(conn, orderId) ?: return@transact MatchPassResult(emptyList(), null)
            if (!incoming.status.live) {
                return@transact MatchPassResult(emptyList(), incoming)
            }
            if (repo.activeFreeze(conn, incoming.objId, incoming.characterId) != null) {
                return@transact MatchPassResult(emptyList(), incoming)
            }
            val item = repo.findItem(conn, incoming.objId) ?: return@transact MatchPassResult(emptyList(), incoming)
            if (item.frozen) {
                return@transact MatchPassResult(emptyList(), incoming)
            }
            val now = clock.instant()
            val fills = mutableListOf<Fill>()
            for (rested in repo.selectCounterOrders(conn, incoming, cfg.maxFillsPerTransaction)) {
                if (incoming.remainingQuantity <= 0) break
                var resting = rested
                val buyer = if (incoming.side == Side.BUY) incoming else resting
                var quantity = minOf(incoming.remainingQuantity, resting.remainingQuantity)
                if (buyer.source == OrderSource.PLAYER) {
                    val allowed = buyLimitRemaining(conn, buyer, item, cfg, now)
                    if (allowed <= 0) {
                        if (buyer.id == incoming.id) break else continue
                    }
                    quantity = minOf(quantity, allowed)
                }
                val price = pricePolicy.price(incoming, resting)
                check(price in minOf(incoming.limitPrice, resting.limitPrice)..maxOf(incoming.limitPrice, resting.limitPrice)) {
                    "execution price $price outside limits of orders ${incoming.id}/${resting.id}"
                }
                val systemOrder = listOf(incoming, resting).firstOrNull { it.source == OrderSource.SYSTEM }
                if (systemOrder != null) {
                    val counterparty = if (systemOrder.id == incoming.id) resting else incoming
                    val allowed = systemAllowance(conn, systemOrder, counterparty, item, price, cfg, now)
                    if (allowed <= 0) {
                        if (systemOrder.id == incoming.id) break else continue
                    }
                    quantity = minOf(quantity, allowed)
                }
                val fill = settle(conn, incoming, resting, quantity, price, item, cfg, now)
                fills += fill
                ExchangeMetrics.fills.incrementAndGet()
                log.info(
                    "exchange fill trade={} correlation={} item={} qty={} price={} buy={} sell={} tax={}",
                    fill.tradeId, incoming.correlationId, item.objId, quantity, price, fill.buyOrderId, fill.sellOrderId, fill.tax,
                )
                incoming = incoming.afterFill(quantity, price)
                resting = resting.afterFill(quantity, price)
            }
            MatchPassResult(fills, incoming)
        }
    }

    fun cancel(orderId: Long, requesterCharacterId: Int?, reason: CancelReason, staffCharacterId: Int? = null, staffReason: String? = null): CancelResult =
        transact { conn ->
            val order = repo.lockOrder(conn, orderId) ?: return@transact CancelResult.Rejected(RejectReason.NOT_FOUND)
            if (requesterCharacterId != null && order.characterId != requesterCharacterId) {
                return@transact CancelResult.Rejected(RejectReason.NOT_OWNER)
            }
            if (order.status.terminal) {
                return@transact CancelResult.AlreadyTerminal(order)
            }
            if (order.status == OrderStatus.PENDING && reason != CancelReason.SYSTEM_FAILURE) {
                return@transact CancelResult.Rejected(RejectReason.INVALID_INPUT)
            }
            terminate(conn, order, OrderStatus.CANCELLED, reason, refund = order.reservedAmount, staffCharacterId, staffReason)
        }

    /** Expires due orders in one batch, one transaction each. Returns how many were expired. */
    fun expireDue(): Int {
        val cfg = config()
        val ids = transact { conn -> repo.selectDueExpiry(conn, clock.instant(), cfg.expiryBatch) }
        var expired = 0
        for (id in ids) {
            val result =
                transact { conn ->
                    val order = repo.lockOrder(conn, id) ?: return@transact null
                    val due = order.expiresAt
                    if (!order.status.live || due == null || due.isAfter(clock.instant())) return@transact null
                    terminate(conn, order, OrderStatus.EXPIRED, null, refund = order.reservedAmount)
                }
            if (result is CancelResult.Cancelled) expired++
        }
        return expired
    }

    /** Matches books the arrival-time pass missed. Returns the number of fills made. */
    fun sweep(): Int {
        val cfg = config()
        val ids = transact { conn -> repo.selectCrossableBuyOrders(conn, cfg.sweepBatch) }
        var fills = 0
        for (id in ids) {
            var pass = matchPass(id)
            fills += pass.fills.size
            while (pass.fills.isNotEmpty() && pass.order?.status?.live == true) {
                pass = matchPass(id)
                fills += pass.fills.size
            }
        }
        return fills
    }

    fun collectionBox(characterId: Int): CollectionBox = transact { conn -> repo.readCollectionBox(conn, characterId) }

    fun findOrder(orderId: Long): ExchangeOrder? = transact { conn -> repo.findOrder(conn, orderId) }

    fun liveOrders(characterId: Int): List<ExchangeOrder> = transact { conn -> repo.selectLiveOrders(conn, characterId) }

    /**
     * First step of a claim: the assets leave the collection box and sit on a PENDING claim row.
     * Debiting here rather than on completion is what stops two claims taking the same items.
     */
    fun beginClaim(
        characterId: Int,
        clientRequestId: String,
        objId: Int?,
        count: Long,
        gp: Long,
        world: Int = 0,
        correlationId: UUID = UUID.randomUUID(),
    ): ClaimResult {
        if (clientRequestId.isBlank() || count < 0 || gp < 0 || (count == 0L && gp == 0L) || (count > 0) != (objId != null)) {
            return ClaimResult.Rejected(RejectReason.INVALID_INPUT)
        }
        return try {
            transact { conn ->
                repo.findClaimByRequest(conn, characterId, clientRequestId)?.let { return@transact ClaimResult.Existing(it) }
                if (repo.hasActiveBan(conn, characterId)) {
                    return@transact ClaimResult.Rejected(RejectReason.BANNED)
                }
                if (repo.accountFrozen(conn, characterId)) {
                    return@transact ClaimResult.Rejected(RejectReason.ACCOUNT_FROZEN)
                }
                if (objId != null) {
                    val held = repo.lockCollectionItems(conn, characterId, objId)
                    if (held < count) {
                        return@transact ClaimResult.Rejected(RejectReason.INSUFFICIENT_COLLECTION, "items")
                    }
                    repo.debitCollectionItems(conn, characterId, objId, count)
                }
                if (gp > 0) {
                    val held = repo.lockCollectionGp(conn, characterId)
                    if (held < gp) {
                        return@transact ClaimResult.Rejected(RejectReason.INSUFFICIENT_COLLECTION, "gp")
                    }
                    repo.debitCollectionGp(conn, characterId, gp)
                }
                val claim = repo.insertClaim(conn, characterId, objId, count, gp, clientRequestId, correlationId, world, clock.instant())
                repo.insertEvent(conn, claimEvent("CLAIM_STARTED", claim))
                ClaimResult.Started(claim)
            }
        } catch (e: SQLException) {
            if (e.sqlState == UNIQUE_VIOLATION) {
                transact { conn -> repo.findClaimByRequest(conn, characterId, clientRequestId) }
                    ?.let { return ClaimResult.Existing(it) }
            }
            throw e
        }
    }

    /** The world has handed the assets over. Idempotent: a finished claim is returned as-is. */
    fun completeClaim(claimId: Long): ExchangeClaim? =
        transact { conn ->
            val claim = repo.lockClaim(conn, claimId) ?: return@transact null
            if (claim.status != ClaimStatus.PENDING) return@transact claim
            repo.finishClaim(conn, claim.id, ClaimStatus.COMPLETE)
            repo.insertEvent(conn, claimEvent("COLLECTION_CLAIMED", claim))
            claim.copy(status = ClaimStatus.COMPLETE)
        }

    /** The world could not hand the assets over; they go back into the box. Idempotent. */
    fun releaseClaim(claimId: Long): ExchangeClaim? =
        transact { conn ->
            val claim = repo.lockClaim(conn, claimId) ?: return@transact null
            if (claim.status != ClaimStatus.PENDING) return@transact claim
            releaseClaimAssets(conn, claim)
            repo.finishClaim(conn, claim.id, ClaimStatus.RELEASED)
            repo.insertEvent(conn, claimEvent("CLAIM_RELEASED", claim))
            claim.copy(status = ClaimStatus.RELEASED)
        }

    /**
     * Login-time recovery. The player save says which two-step actions got as far as moving the
     * player's assets; everything else stuck in PENDING is resolved the safe way.
     */
    fun recover(characterId: Int, markers: RecoveryMarkers): List<RecoveryAction> {
        val cfg = config()
        val cutoff = clock.instant().minus(cfg.recoveryGrace)
        val actions = mutableListOf<RecoveryAction>()
        val orderIds = transact { conn -> repo.selectStalePendingOrders(conn, characterId, cutoff) }
        for (id in orderIds) {
            transact { conn ->
                val order = repo.lockOrder(conn, id) ?: return@transact
                if (order.status != OrderStatus.PENDING || order.characterId != characterId) return@transact
                val refund = if (id in markers.escrowOrderIds) order.expectedReservation(order.quantity) else 0L
                if (refund > 0) {
                    // The assets did leave the player, so the ledger records the reservation before its release.
                    repo.insertEvent(
                        conn,
                        event(
                            if (order.side == Side.BUY) "BUY_FUNDS_RESERVED" else "SELL_ITEMS_RESERVED",
                            order,
                            quantity = order.quantity,
                            amount = refund,
                        ),
                    )
                }
                terminate(conn, order, OrderStatus.CANCELLED, CancelReason.SYSTEM_FAILURE, refund = refund)
                repo.insertEvent(
                    conn,
                    event("RECOVERY_ACTION", order, amount = refund, reason = if (refund > 0) "pending order refunded" else "pending order dropped"),
                )
                actions += if (refund > 0) RecoveryAction.OrderRefunded(id) else RecoveryAction.OrderDropped(id)
            }
        }
        val claimIds = transact { conn -> repo.selectStalePendingClaims(conn, characterId, cutoff) }
        for (id in claimIds) {
            transact { conn ->
                val claim = repo.lockClaim(conn, id) ?: return@transact
                if (claim.status != ClaimStatus.PENDING || claim.characterId != characterId) return@transact
                if (id in markers.pendingClaimIds) {
                    repo.finishClaim(conn, id, ClaimStatus.COMPLETE)
                    repo.insertEvent(conn, claimEvent("COLLECTION_CLAIMED", claim))
                    repo.insertEvent(conn, claimEvent("RECOVERY_ACTION", claim, "pending claim completed"))
                    actions += RecoveryAction.ClaimCompleted(id)
                } else {
                    releaseClaimAssets(conn, claim)
                    repo.finishClaim(conn, id, ClaimStatus.RELEASED)
                    repo.insertEvent(conn, claimEvent("CLAIM_RELEASED", claim))
                    repo.insertEvent(conn, claimEvent("RECOVERY_ACTION", claim, "pending claim released"))
                    actions += RecoveryAction.ClaimReleased(id)
                }
            }
        }
        return actions
    }

    fun undeliveredNotifications(characterId: Int, limit: Int = 50): List<ExchangeNotification> =
        transact { conn -> repo.selectUndeliveredNotifications(conn, characterId, limit) }

    fun findNotification(id: Long): ExchangeNotification? = transact { conn -> repo.findNotification(conn, id) }

    fun markNotificationsDelivered(ids: Collection<Long>, world: Int): Int =
        transact { conn -> repo.markNotificationsDelivered(conn, ids, world, clock.instant()) }

    /** A player's own history: orders, fills, tax paid. Never counterparties. */
    fun playerHistory(characterId: Int, limit: Int = 50): List<PlayerOrderHistory> =
        transact { conn -> repo.playerHistory(conn, characterId, limit) }

    /**
     * Records a rejected request and flags the account once rejections look like probing or
     * churn. Called by the world for anything it refused before reaching the database too.
     */
    fun recordRejection(characterId: Int, reasonCode: String, world: Int = 0, detail: String? = null) {
        val cfg = config()
        transact { conn ->
            val now = clock.instant()
            repo.insertEvent(
                conn,
                ExchangeRepository.EventRow(
                    type = "REQUEST_REJECTED", characterId = characterId, correlationId = UUID.randomUUID(),
                    reason = reasonCode, world = world, metadata = """{"detail":${detail?.let { "\"${it.replace("\"", "'")}\"" } ?: "null"}}""",
                ),
            )
            val invalid = if (reasonCode == "INVALID_INPUT" || reasonCode == "MAX_VALUE" || reasonCode == "MAX_QUANTITY") 1 else 0
            val trips = if (reasonCode == "RATE_LIMITED") 1 else 0
            if (invalid == 0 && trips == 0) return@transact
            val (invalidTotal, tripTotal) = repo.bumpAccountState(conn, characterId, invalid, trips, now)
            val dayAgo = now.minus(Duration.ofHours(24))
            if (invalid > 0 && invalidTotal >= cfg.ops.probingFlagThreshold && !repo.recentFlagExists(conn, "INVALID_INPUT_PROBING", characterId, dayAgo)) {
                repo.insertFlag(conn, "INVALID_INPUT_PROBING", characterId, null, null, """{"invalid_inputs":$invalidTotal}""", now)
            }
            if (trips > 0 && tripTotal >= cfg.ops.rateLimitFlagThreshold && !repo.recentFlagExists(conn, "RATE_LIMITED", characterId, dayAgo)) {
                repo.insertFlag(conn, "RATE_LIMITED", characterId, null, null, """{"rate_limit_trips":$tripTotal}""", now)
            }
        }
    }

    /** Cancels every live order in an item with [reason] (delisting, item freeze without hold). */
    fun cancelAllForItem(objId: Int, reason: CancelReason, staffCharacterId: Int? = null, staffReason: String? = null): Int {
        val ids = transact { conn -> repo.selectLiveOrderIdsByItem(conn, objId) }
        var cancelled = 0
        for (id in ids) {
            if (cancel(id, null, reason, staffCharacterId, staffReason) is CancelResult.Cancelled) cancelled++
        }
        return cancelled
    }

    /** Cancels every live order of every character on an account (ban at account scope). */
    fun cancelAllForAccount(accountId: Long, reason: CancelReason): Int {
        val characters = transact { conn -> repo.charactersOfAccount(conn, accountId) }
        return characters.sumOf { cancelAllLive(it, reason) }
    }

    /** Cancels every live order of a character with [reason], returning the assets to their collection box. */
    fun cancelAllLive(characterId: Int, reason: CancelReason, staffCharacterId: Int? = null, staffReason: String? = null): Int {
        val ids = transact { conn -> repo.selectLiveOrders(conn, characterId).filter { it.status.live }.map { it.id } }
        var cancelled = 0
        for (id in ids) {
            if (cancel(id, null, reason, staffCharacterId, staffReason) is CancelResult.Cancelled) cancelled++
        }
        return cancelled
    }

    private fun settle(
        conn: Connection,
        incoming: ExchangeOrder,
        resting: ExchangeOrder,
        quantity: Long,
        price: Long,
        item: ExchangeItem,
        cfg: ExchangeConfig,
        now: Instant,
    ): Fill {
        val buyer = if (incoming.side == Side.BUY) incoming else resting
        val seller = if (incoming.side == Side.SELL) incoming else resting
        val gross = Math.multiplyExact(price, quantity)
        val rateBps = cfg.rateBpsFor(item.taxCategory)
        val unitTax = TaxPolicy.unitTax(price, rateBps, cfg.taxCapPerItem, item.taxExempt)
        val tax = TaxPolicy.fillTax(unitTax, quantity)
        val net = gross - tax

        val buyerFilled = buyer.filledQuantity + quantity
        val buyerRemaining = buyer.quantity - buyerFilled
        val buyerReserved = Math.multiplyExact(buyerRemaining, buyer.limitPrice)
        val released = Math.multiplyExact(buyer.limitPrice - price, quantity)
        check(buyer.reservedAmount - buyerReserved == gross + released) { "buyer reservation drift on order ${buyer.id}" }
        val buyerStatus = if (buyerRemaining == 0L) OrderStatus.FILLED else OrderStatus.PARTIALLY_FILLED

        val sellerFilled = seller.filledQuantity + quantity
        val sellerRemaining = seller.quantity - sellerFilled
        val sellerStatus = if (sellerRemaining == 0L) OrderStatus.FILLED else OrderStatus.PARTIALLY_FILLED

        repo.applyFill(conn, buyer.id, buyerFilled, buyerReserved, buyerStatus)
        repo.applyFill(conn, seller.id, sellerFilled, sellerRemaining, sellerStatus)

        val source =
            when {
                buyer.source == OrderSource.ADMIN || seller.source == OrderSource.ADMIN -> OrderSource.ADMIN
                buyer.source == OrderSource.SYSTEM || seller.source == OrderSource.SYSTEM -> OrderSource.SYSTEM
                else -> OrderSource.PLAYER
            }
        val (tradeId, _) =
            repo.insertTrade(
                conn,
                ExchangeRepository.TradeRow(
                    buyOrderId = buyer.id,
                    sellOrderId = seller.id,
                    buyerCharacterId = buyer.characterId,
                    sellerCharacterId = seller.characterId,
                    makerSide = resting.side,
                    objId = item.objId,
                    quantity = quantity,
                    unitPrice = price,
                    grossValue = gross,
                    taxRateBps = if (item.taxExempt) 0 else rateBps,
                    tax = tax,
                    netValue = net,
                    source = source,
                    flags = if (source == OrderSource.PLAYER) emptyList() else listOf(source.name),
                    buyFilledBefore = buyer.filledQuantity,
                    correlationId = incoming.correlationId,
                    world = incoming.world,
                ),
            )

        repo.creditCollectionItems(conn, buyer.characterId, item.objId, quantity)
        repo.creditCollectionGp(conn, buyer.characterId, released)
        repo.creditCollectionGp(conn, seller.characterId, net)

        val correlation = incoming.correlationId
        repo.insertEvent(conn, ExchangeRepository.EventRow("TRADE_EXECUTED", buyer.characterId, buyer.id, tradeId, null, item.objId, quantity, gross, correlation, world = incoming.world, metadata = """{"seller_character_id":${seller.characterId},"sell_order_id":${seller.id},"unit_price":$price}"""))
        repo.insertEvent(conn, ExchangeRepository.EventRow("ITEM_CREDITED", buyer.characterId, buyer.id, tradeId, null, item.objId, quantity, null, correlation, world = incoming.world))
        if (released > 0) {
            repo.insertEvent(conn, ExchangeRepository.EventRow("RESERVATION_RELEASED", buyer.characterId, buyer.id, tradeId, null, item.objId, null, released, correlation, world = incoming.world))
        }
        repo.insertEvent(conn, ExchangeRepository.EventRow("GP_CREDITED", seller.characterId, seller.id, tradeId, null, item.objId, quantity, net, correlation, world = incoming.world))
        if (tax > 0) {
            repo.insertEvent(conn, ExchangeRepository.EventRow("TAX_SINKED", seller.characterId, seller.id, tradeId, null, item.objId, quantity, tax, correlation, world = incoming.world, metadata = """{"rate_bps":$rateBps,"unit_tax":$unitTax}"""))
        }

        val bucket = now.truncatedTo(ChronoUnit.HOURS)
        if (buyer.source == OrderSource.PLAYER) {
            repo.addBuyLimitUsage(conn, buyer.characterId, item.objId, bucket, quantity)
        }
        val systemOrder = listOf(buyer, seller).firstOrNull { it.source == OrderSource.SYSTEM }
        if (systemOrder != null) {
            val counterparty = if (systemOrder.id == buyer.id) seller else buyer
            repo.addSystemUsage(conn, item.objId, systemOrder.side, bucket, 0, quantity, gross)
            repo.addSystemUsage(conn, item.objId, systemOrder.side, bucket, counterparty.characterId, quantity, gross)
        }
        repo.addHourlyStats(conn, item.objId, bucket, price, quantity, gross, source, tax)
        repo.addHourlyTrader(conn, item.objId, bucket, buyer.characterId, Side.BUY, quantity)
        repo.addHourlyTrader(conn, item.objId, bucket, seller.characterId, Side.SELL, quantity)

        notifyFill(conn, buyer, buyerStatus, quantity, price)
        notifyFill(conn, seller, sellerStatus, quantity, price)

        return Fill(tradeId, buyer.id, seller.id, quantity, price, tax)
    }

    private fun terminate(
        conn: Connection,
        order: ExchangeOrder,
        status: OrderStatus,
        reason: CancelReason?,
        refund: Long,
        staffCharacterId: Int? = null,
        staffReason: String? = null,
    ): CancelResult {
        var releasedGp = 0L
        var releasedItems = 0L
        if (refund > 0) {
            if (order.side == Side.BUY) {
                repo.creditCollectionGp(conn, order.characterId, refund)
                releasedGp = refund
            } else {
                repo.creditCollectionItems(conn, order.characterId, order.objId, refund)
                releasedItems = refund
            }
            repo.insertEvent(conn, event("RESERVATION_RELEASED", order, quantity = releasedItems.takeIf { it > 0 }, amount = releasedGp.takeIf { it > 0 }))
        }
        repo.terminateOrder(conn, order.id, status, reason)
        repo.insertEvent(
            conn,
            event(
                if (status == OrderStatus.EXPIRED) "ORDER_EXPIRED" else "ORDER_CANCELLED",
                order,
                quantity = order.remainingQuantity,
                staffCharacterId = staffCharacterId,
                reason = staffReason ?: reason?.name,
            ),
        )
        if (order.status.live && reason != CancelReason.PLAYER) {
            val kind = if (status == OrderStatus.EXPIRED) "ORDER_EXPIRED" else "ORDER_CANCELLED_SYSTEM"
            repo.insertNotification(
                conn,
                order.characterId,
                kind,
                order.id,
                order.objId,
                """{"side":"${order.side}","remaining":${order.remainingQuantity},"reason":"${reason?.name ?: status.name}"}""",
            )
        }
        val updated = order.copy(status = status, cancelReason = reason, reservedAmount = 0)
        if (status == OrderStatus.EXPIRED) ExchangeMetrics.ordersExpired.incrementAndGet() else ExchangeMetrics.ordersCancelled.incrementAndGet()
        log.info(
            "exchange order {} order={} correlation={} character={} item={} reason={} releasedGp={} releasedItems={}",
            status, order.id, order.correlationId, order.characterId, order.objId, reason, releasedGp, releasedItems,
        )
        return CancelResult.Cancelled(updated, releasedGp, releasedItems)
    }

    private fun releaseClaimAssets(conn: Connection, claim: ExchangeClaim) {
        if (claim.objId != null && claim.count > 0) {
            repo.creditCollectionItems(conn, claim.characterId, claim.objId, claim.count)
        }
        if (claim.gp > 0) {
            repo.creditCollectionGp(conn, claim.characterId, claim.gp)
        }
    }

    /**
     * How much a SYSTEM order may still trade right now: its item's hourly and daily caps, the
     * counterparty's daily cap against the system, and the global daily GP (system buying) or
     * item (system selling) cap. Enforced here, in settlement, not only when the order is placed.
     */
    private fun systemAllowance(
        conn: Connection,
        systemOrder: ExchangeOrder,
        counterparty: ExchangeOrder,
        item: ExchangeItem,
        price: Long,
        cfg: ExchangeConfig,
        now: Instant,
    ): Long {
        val liquidity = repo.findLiquidity(conn, item.objId) ?: return 0
        if (!liquidity.enabled) return 0
        val side = systemOrder.side
        val hourStart = now.truncatedTo(ChronoUnit.HOURS)
        val dayStart = now.truncatedTo(ChronoUnit.DAYS)
        val hourlyCap = if (side == Side.BUY) liquidity.hourlyCapBuy else liquidity.hourlyCapSell
        val dailyCap = if (side == Side.BUY) liquidity.dailyCapBuy else liquidity.dailyCapSell
        val (hourUsed, _) = repo.systemUsage(conn, item.objId, side, 0, hourStart.minusMillis(1))
        val (dayUsed, _) = repo.systemUsage(conn, item.objId, side, 0, dayStart.minusMillis(1))
        var allowed = minOf(hourlyCap - hourUsed, dailyCap - dayUsed)
        if (liquidity.perAccountDailyCap > 0) {
            val (accountUsed, _) = repo.systemUsage(conn, item.objId, side, counterparty.characterId, dayStart.minusMillis(1))
            allowed = minOf(allowed, liquidity.perAccountDailyCap - accountUsed)
        }
        val (globalFilled, globalGp) = repo.systemUsageGlobal(conn, side, dayStart.minusMillis(1))
        allowed =
            if (side == Side.BUY) {
                minOf(allowed, (cfg.liquidity.globalDailyGpCap - globalGp) / price)
            } else {
                minOf(allowed, cfg.liquidity.globalDailyItemCap - globalFilled)
            }
        return allowed.coerceAtLeast(0)
    }

    private fun buyLimitRemaining(conn: Connection, buyer: ExchangeOrder, item: ExchangeItem, cfg: ExchangeConfig, now: Instant): Long {
        val limit = item.effectiveBuyLimit(now, cfg.defaultBuyLimit).toLong()
        val used = repo.buyLimitUsed(conn, buyer.characterId, item.objId, now.minus(cfg.buyLimitWindow))
        return limit - used
    }

    private fun notifyFill(conn: Connection, order: ExchangeOrder, status: OrderStatus, quantity: Long, price: Long) {
        val kind = if (status == OrderStatus.FILLED) "ORDER_FILLED" else "ORDER_PARTIALLY_FILLED"
        repo.insertNotification(
            conn,
            order.characterId,
            kind,
            order.id,
            order.objId,
            """{"side":"${order.side}","quantity":$quantity,"unit_price":$price,"filled":${order.filledQuantity + quantity},"total":${order.quantity}}""",
        )
    }

    private fun event(
        type: String,
        order: ExchangeOrder,
        quantity: Long? = null,
        amount: Long? = null,
        staffCharacterId: Int? = null,
        reason: String? = null,
    ) = ExchangeRepository.EventRow(
        type = type,
        characterId = order.characterId,
        orderId = order.id,
        objId = order.objId,
        quantity = quantity,
        amount = amount,
        correlationId = order.correlationId,
        staffCharacterId = staffCharacterId,
        reason = reason,
        world = order.world,
    )

    private fun claimEvent(type: String, claim: ExchangeClaim, reason: String? = null) =
        ExchangeRepository.EventRow(
            type = type,
            characterId = claim.characterId,
            claimId = claim.id,
            objId = claim.objId,
            quantity = claim.count.takeIf { it > 0 },
            amount = claim.gp.takeIf { it > 0 },
            correlationId = claim.correlationId,
            reason = reason,
        )

    private fun ExchangeOrder.afterFill(quantity: Long, price: Long): ExchangeOrder {
        val filled = filledQuantity + quantity
        val remaining = this.quantity - filled
        return copy(
            filledQuantity = filled,
            reservedAmount = expectedReservation(remaining),
            status = if (remaining == 0L) OrderStatus.FILLED else OrderStatus.PARTIALLY_FILLED,
        )
    }

    private fun FreezeScope.rejectReason(): RejectReason =
        when (this) {
            FreezeScope.GLOBAL -> RejectReason.TRADING_FROZEN
            FreezeScope.ITEM -> RejectReason.ITEM_FROZEN
            FreezeScope.ACCOUNT -> RejectReason.ACCOUNT_FROZEN
        }

    private inline fun <T> transact(block: (Connection) -> T): T {
        val cfg = config()
        var attempt = 0
        while (true) {
            try {
                dataSource.connection.use { conn ->
                    val previous = conn.autoCommit
                    conn.autoCommit = false
                    try {
                        val result = block(conn)
                        conn.commit()
                        return result
                    } catch (t: Throwable) {
                        runCatching { conn.rollback() }
                        throw t
                    } finally {
                        runCatching { conn.autoCommit = previous }
                    }
                }
            } catch (e: SQLException) {
                if (e.sqlState in RETRYABLE && attempt < cfg.transactionRetries) {
                    attempt++
                    ExchangeMetrics.retries.incrementAndGet()
                    if (e.sqlState == "40P01") ExchangeMetrics.deadlocks.incrementAndGet()
                    log.warn("exchange transaction retry {} after {}", attempt, e.sqlState)
                    Thread.sleep(cfg.transactionBackoffMs * attempt)
                    continue
                }
                throw e
            }
        }
    }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        val RETRYABLE = setOf("40001", "40P01")
    }
}
