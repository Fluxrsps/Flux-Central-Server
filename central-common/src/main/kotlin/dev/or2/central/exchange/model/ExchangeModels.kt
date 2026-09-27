package dev.or2.central.exchange.model

import java.time.Instant
import java.util.UUID

enum class Side {
    BUY,
    SELL,
    ;

    val opposite: Side
        get() = if (this == BUY) SELL else BUY
}

enum class OrderSource { PLAYER, SYSTEM, ADMIN }

enum class OrderStatus {
    PENDING,
    OPEN,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    EXPIRED,
    ;

    val live: Boolean
        get() = this == OPEN || this == PARTIALLY_FILLED

    val terminal: Boolean
        get() = this == FILLED || this == CANCELLED || this == EXPIRED
}

enum class CancelReason { PLAYER, BANNED, SYSTEM_FAILURE, FROZEN, DELISTED }

enum class RejectReason {
    INVALID_INPUT,
    UNKNOWN_ITEM,
    ITEM_FROZEN,
    TRADING_FROZEN,
    ACCOUNT_FROZEN,
    SLOT_LIMIT,
    MAX_QUANTITY,
    MAX_VALUE,
    BANNED,
    NOT_OWNER,
    NOT_FOUND,
    INSUFFICIENT_COLLECTION,
    TRADING_UNAVAILABLE,
}

data class ExchangeItem(
    val objId: Int,
    val frozen: Boolean,
    val basePrice: Long?,
    val buyLimit: Int?,
    val taxCategory: String,
    val taxExempt: Boolean,
    val launchBuyLimit: Int? = null,
    val launchLimitUntil: Instant? = null,
) {
    fun effectiveBuyLimit(now: Instant, default: Int): Int {
        val launch = launchBuyLimit
        val until = launchLimitUntil
        if (launch != null && until != null && now.isBefore(until)) return launch
        return buyLimit ?: default
    }
}

data class ExchangeNotification(
    val id: Long,
    val characterId: Int,
    val kind: String,
    val orderId: Long?,
    val objId: Int?,
    val payload: String,
    val deliveredAt: Instant?,
)

data class PlayerOrderHistory(
    val orderId: Long,
    val side: Side,
    val objId: Int,
    val quantity: Long,
    val filledQuantity: Long,
    val limitPrice: Long,
    val status: OrderStatus,
    val createdAt: Instant,
    val taxPaid: Long,
    val averagePrice: Long?,
)

data class ExchangeOrder(
    val id: Long,
    val characterId: Int,
    val objId: Int,
    val side: Side,
    val source: OrderSource,
    val quantity: Long,
    val filledQuantity: Long,
    val limitPrice: Long,
    val reservedAmount: Long,
    val status: OrderStatus,
    val cancelReason: CancelReason?,
    val clientRequestId: String,
    val correlationId: UUID,
    val world: Int,
    val createdAt: Instant,
    val expiresAt: Instant?,
) {
    val remainingQuantity: Long
        get() = quantity - filledQuantity

    fun expectedReservation(remaining: Long = remainingQuantity): Long =
        if (side == Side.BUY) remaining * limitPrice else remaining
}

data class ExchangeTrade(
    val id: Long,
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
    val executedAt: Instant,
)

data class CollectionBox(val items: Map<Int, Long>, val gp: Long) {
    val isEmpty: Boolean
        get() = items.isEmpty() && gp == 0L

    companion object {
        val EMPTY = CollectionBox(emptyMap(), 0)
    }
}

enum class ClaimStatus { PENDING, COMPLETE, RELEASED }

data class ExchangeClaim(
    val id: Long,
    val characterId: Int,
    val status: ClaimStatus,
    val objId: Int?,
    val count: Long,
    val gp: Long,
    val clientRequestId: String,
    val correlationId: UUID,
    val createdAt: Instant,
)

enum class FreezeScope { GLOBAL, ITEM, ACCOUNT }

data class Fill(
    val tradeId: Long,
    val buyOrderId: Long,
    val sellOrderId: Long,
    val quantity: Long,
    val unitPrice: Long,
    val tax: Long,
)

data class CreateOrderRequest(
    val characterId: Int,
    val objId: Int,
    val side: Side,
    val quantity: Long,
    val limitPrice: Long,
    val clientRequestId: String,
    val correlationId: UUID = UUID.randomUUID(),
    val source: OrderSource = OrderSource.PLAYER,
    val world: Int = 0,
    val expiresAt: Instant? = null,
    val createdBy: Int? = null,
)

sealed interface CreateOrderResult {
    data class Pending(val order: ExchangeOrder) : CreateOrderResult

    data class Existing(val order: ExchangeOrder) : CreateOrderResult

    data class Rejected(val reason: RejectReason, val detail: String? = null) : CreateOrderResult
}

sealed interface OpenOrderResult {
    data class Opened(val order: ExchangeOrder) : OpenOrderResult

    data class AlreadyOpened(val order: ExchangeOrder) : OpenOrderResult

    data class Rejected(val reason: RejectReason, val detail: String? = null) : OpenOrderResult
}

data class MatchPassResult(val fills: List<Fill>, val order: ExchangeOrder?) {
    val filledQuantity: Long
        get() = fills.sumOf { it.quantity }
}

sealed interface CancelResult {
    data class Cancelled(val order: ExchangeOrder, val releasedGp: Long, val releasedItems: Long) : CancelResult

    data class AlreadyTerminal(val order: ExchangeOrder) : CancelResult

    data class Rejected(val reason: RejectReason) : CancelResult
}

sealed interface ClaimResult {
    data class Started(val claim: ExchangeClaim) : ClaimResult

    data class Existing(val claim: ExchangeClaim) : ClaimResult

    data class Rejected(val reason: RejectReason, val detail: String? = null) : ClaimResult
}

data class RecoveryMarkers(val escrowOrderIds: Set<Long>, val pendingClaimIds: Set<Long>)

sealed interface RecoveryAction {
    val id: Long

    data class OrderRefunded(override val id: Long) : RecoveryAction

    data class OrderDropped(override val id: Long) : RecoveryAction

    data class ClaimCompleted(override val id: Long) : RecoveryAction

    data class ClaimReleased(override val id: Long) : RecoveryAction
}
