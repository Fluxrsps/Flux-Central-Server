package dev.or2.central.exchange.pricing

import dev.or2.central.exchange.PricingConfig
import dev.or2.central.exchange.model.OrderSource
import dev.or2.central.exchange.model.Side
import java.time.Duration
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

class MarketPriceCalculator(private val config: PricingConfig = PricingConfig()) {
    fun compute(inputs: MarketPriceInputs): MarketPriceResult? {
        val cleaned = clean(inputs)
        val oldPrice = inputs.oldPrice ?: initialPrice(inputs, cleaned) ?: return null
        val reason = if (inputs.oldPrice == null) "INITIAL_BASE_PRICE" else "SCHEDULED"

        val real = cleaned.kept.filter { !it.trade.seeded }
        val distinctBuyers = real.mapNotNull { it.trade.buyerCharacterId }.toSet().size
        val distinctSellers = real.mapNotNull { it.trade.sellerCharacterId }.toSet().size
        val confidence = confidence(real, distinctBuyers, distinctSellers, inputs)
        val launchState =
            if (inputs.launchState == LaunchState.DISCOVERY && confidence >= config.discoveryConfidenceThreshold) LaunchState.NORMAL
            else inputs.launchState

        val seededWeight = if (confidence >= config.discoveryConfidenceThreshold) 0.0 else config.seededWeight
        val evidence = cleaned.kept.filter { !it.trade.seeded || seededWeight > 0 }
        val totalWeight = evidence.sumOf { it.weight * if (it.trade.seeded) seededWeight else 1.0 }

        val (buyDepth, sellDepth) = depth(inputs, oldPrice)
        val pressure = pressure(buyDepth, sellDepth)
        val anchorPrice = inputs.basePrice
        val anchorWeight = if (anchorPrice == null) 0.0 else config.anchorMaxWeight * (1 - confidence).pow(config.anchorCurve)

        if (totalWeight <= 0.0) {
            val drifted = drift(oldPrice, anchorPrice)
            return MarketPriceResult(
                inputs.objId, oldPrice, drifted, null, pressure, confidence, anchorWeight, anchorPrice, null, null,
                maxChange(confidence, launchState), false, 0, cleaned.excluded, distinctBuyers, distinctSellers,
                buyDepth, sellDepth, seededWeight, launchState, reason,
            )
        }

        val vwap = evidence.sumOf { it.trade.unitPrice * it.weight * if (it.trade.seeded) seededWeight else 1.0 } / totalWeight
        val marketTarget = vwap * (1 + pressure)
        val anchoredTarget = if (anchorPrice == null) marketTarget else marketTarget * (1 - anchorWeight) + anchorPrice * anchorWeight
        val maxChange = maxChange(confidence, launchState)
        val unclamped = oldPrice + (anchoredTarget - oldPrice) * confidence * config.smoothing
        val floor = oldPrice * (1 - maxChange)
        val ceiling = oldPrice * (1 + maxChange)
        val bounded = unclamped.coerceIn(floor, ceiling)
        val newPrice = max(1L, bounded.roundHalfUp())

        return MarketPriceResult(
            objId = inputs.objId,
            oldPrice = oldPrice,
            newPrice = newPrice,
            vwap = vwap,
            pressure = pressure,
            confidence = confidence,
            anchorWeight = anchorWeight,
            anchorPrice = anchorPrice,
            marketTarget = marketTarget,
            anchoredTarget = anchoredTarget,
            maxChange = maxChange,
            clamped = bounded != unclamped,
            tradesUsed = evidence.size,
            tradesExcluded = cleaned.excluded,
            distinctBuyers = distinctBuyers,
            distinctSellers = distinctSellers,
            buyDepth = buyDepth,
            sellDepth = sellDepth,
            seededWeight = seededWeight,
            launchState = launchState,
            reason = reason,
        )
    }

    private class Weighted(val trade: PriceTrade, var weight: Double)

    private class Cleaned(val kept: List<Weighted>, val excluded: Int)

    private fun clean(inputs: MarketPriceInputs): Cleaned {
        val windowStart = inputs.now.minus(Duration.ofHours(config.tradeWindowHours.toLong()))
        val seedCutoff = inputs.now.minus(Duration.ofDays(config.seedMaxAgeDays.toLong()))
        var excluded = 0
        val eligible =
            inputs.trades.filter { trade ->
                val keep =
                    when {
                        trade.executedAt.isBefore(windowStart) -> false
                        trade.seeded -> !trade.executedAt.isBefore(seedCutoff)
                        trade.source != OrderSource.PLAYER -> false
                        trade.flags.contains(PriceTrade.LINKED_FLAG) || trade.flags.contains(PriceTrade.REVERSED_FLAG) -> false
                        trade.buyerCharacterId != null && trade.buyerCharacterId == trade.sellerCharacterId -> false
                        else -> true
                    }
                if (!keep) excluded++
                keep
            }

        val reference = inputs.oldPrice
        val afterOutliers =
            if (reference == null) {
                eligible
            } else {
                val band = reference * config.outlierBandBps / 10_000.0
                val (outliers, inBand) = eligible.partition { abs(it.unitPrice - reference) > band }
                val outlierAccounts = outliers.flatMap { listOfNotNull(it.buyerCharacterId, it.sellerCharacterId) }.toSet()
                val realMove = outliers.size >= config.outlierOverrideMinTrades && outlierAccounts.size >= config.outlierOverrideMinAccounts
                if (realMove) {
                    eligible
                } else {
                    excluded += outliers.size
                    inBand
                }
            }

        val weighted = afterOutliers.map { Weighted(it, it.quantity * decay(inputs, it)) }
        capAccounts(weighted)
        return Cleaned(weighted, excluded)
    }

    private fun decay(inputs: MarketPriceInputs, trade: PriceTrade): Double {
        val ageHours = Duration.between(trade.executedAt, inputs.now).toMillis() / 3_600_000.0
        return exp(-ln(2.0) * ageHours / config.vwapHalfLifeHours)
    }

    private fun capAccounts(weighted: List<Weighted>) {
        val cap = config.maxAccountWeightBps / 10_000.0
        if (cap >= 1.0) return
        val real = weighted.filter { !it.trade.seeded }
        repeat(5) {
            val total = real.sumOf { it.weight }
            if (total <= 0.0) return
            val share = HashMap<Int, Double>()
            for (w in real) {
                w.trade.buyerCharacterId?.let { share.merge(it, w.weight, Double::plus) }
                w.trade.sellerCharacterId?.let { share.merge(it, w.weight, Double::plus) }
            }
            val factor = HashMap<Int, Double>()
            for ((account, weight) in share) {
                if (weight > cap * total) {
                    factor[account] = (cap / (1 - cap)) * (total - weight) / weight
                }
            }
            if (factor.isEmpty()) return
            for (w in real) {
                val f = min(factor[w.trade.buyerCharacterId] ?: 1.0, factor[w.trade.sellerCharacterId] ?: 1.0)
                w.weight *= f
            }
        }
    }

    private fun confidence(real: List<Weighted>, distinctBuyers: Int, distinctSellers: Int, inputs: MarketPriceInputs): Double {
        if (real.isEmpty()) return 0.0
        val distinct = min(distinctBuyers, distinctSellers).toDouble() / config.confidenceFullDistinctTraders
        val trades = real.size.toDouble() / config.confidenceFullTrades
        val latest = real.maxOf { it.trade.executedAt }
        val hoursSince = Duration.between(latest, inputs.now).toMillis() / 3_600_000.0
        val recency = exp(-hoursSince / config.confidenceRecencyHours)
        return (min(1.0, distinct).pow(0.6) * min(1.0, trades).pow(0.2) * recency.pow(0.2)).coerceIn(0.0, 1.0)
    }

    private fun depth(inputs: MarketPriceInputs, reference: Long): Pair<Long, Long> {
        val band = reference * config.depthBandBps / 10_000.0
        val oldEnough = inputs.now.minus(Duration.ofMinutes(config.minOrderAgeMinutes.toLong()))
        var buy = 0L
        var sell = 0L
        for (order in inputs.depth) {
            if (order.createdAt.isAfter(oldEnough)) continue
            if (abs(order.limitPrice - reference) > band) continue
            if (order.side == Side.BUY) buy += order.remaining else sell += order.remaining
        }
        return buy to sell
    }

    private fun pressure(buyDepth: Long, sellDepth: Long): Double {
        val total = buyDepth + sellDepth
        if (total <= 0) return 0.0
        val imbalance = (buyDepth - sellDepth).toDouble() / total
        val limit = config.maxPressureBps / 10_000.0
        return (imbalance * config.pressureSensitivity).coerceIn(-limit, limit)
    }

    private fun maxChange(confidence: Double, launchState: LaunchState): Double {
        if (launchState == LaunchState.DISCOVERY) return config.discoveryMaxChangeBps / 10_000.0
        val low = config.maxChangeLowBps / 10_000.0
        val high = config.maxChangeHighBps / 10_000.0
        return low + (high - low) * confidence
    }

    private fun drift(oldPrice: Long, anchor: Long?): Long {
        if (anchor == null || config.noTradeDriftBps == 0 || anchor == oldPrice) return oldPrice
        val step = oldPrice * config.noTradeDriftBps / 10_000.0
        val moved = if (anchor > oldPrice) min(anchor.toDouble(), oldPrice + step) else max(anchor.toDouble(), oldPrice - step)
        return max(1L, moved.roundHalfUp())
    }

    private fun initialPrice(inputs: MarketPriceInputs, cleaned: Cleaned): Long? {
        inputs.basePrice?.let { return it }
        val real = cleaned.kept.filter { !it.trade.seeded }
        val total = real.sumOf { it.weight }
        if (total <= 0.0) return null
        return max(1L, (real.sumOf { it.trade.unitPrice * it.weight } / total).roundHalfUp())
    }

    private fun Double.roundHalfUp(): Long = (this + 0.5).let { kotlin.math.floor(it) }.roundToLong()
}
