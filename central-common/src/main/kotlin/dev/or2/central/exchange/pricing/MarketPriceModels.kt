package dev.or2.central.exchange.pricing

import dev.or2.central.exchange.model.OrderSource
import dev.or2.central.exchange.model.Side
import java.time.Instant

data class PriceTrade(
    val buyerCharacterId: Int?,
    val sellerCharacterId: Int?,
    val quantity: Long,
    val unitPrice: Long,
    val source: OrderSource,
    val flags: List<String>,
    val executedAt: Instant,
) {
    val seeded: Boolean
        get() = flags.contains(SEEDED_FLAG)

    companion object {
        const val SEEDED_FLAG = "SEEDED"
        const val LINKED_FLAG = "LINKED_ACCOUNTS"
        const val REVERSED_FLAG = "REVERSED"
    }
}

data class DepthOrder(val side: Side, val limitPrice: Long, val remaining: Long, val createdAt: Instant)

enum class LaunchState { NORMAL, DISCOVERY }

data class MarketPriceInputs(
    val objId: Int,
    val oldPrice: Long?,
    val basePrice: Long?,
    val launchState: LaunchState,
    val trades: List<PriceTrade>,
    val depth: List<DepthOrder>,
    val now: Instant,
)

/** Everything the algorithm decided and why; stored verbatim in the history table. */
data class MarketPriceResult(
    val objId: Int,
    val oldPrice: Long,
    val newPrice: Long,
    val vwap: Double?,
    val pressure: Double,
    val confidence: Double,
    val anchorWeight: Double,
    val anchorPrice: Long?,
    val marketTarget: Double?,
    val anchoredTarget: Double?,
    val maxChange: Double,
    val clamped: Boolean,
    val tradesUsed: Int,
    val tradesExcluded: Int,
    val distinctBuyers: Int,
    val distinctSellers: Int,
    val buyDepth: Long,
    val sellDepth: Long,
    val seededWeight: Double,
    val launchState: LaunchState,
    val reason: String,
) {
    val changed: Boolean
        get() = oldPrice != newPrice
}
