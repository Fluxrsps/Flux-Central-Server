package dev.or2.central.exchange

import dev.or2.sql.OpenRuneSql
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import javax.sql.DataSource

/**
 * Every economic parameter the core exchange reads. Defaults here are the shipped values; any
 * row in exchange_config overrides the property of the same name.
 *
 * - taxRateBps 200 and taxCapPerItem 5M are the OSRS values players already know.
 * - maxActiveOrders 8 is the OSRS members slot count.
 * - maxOrderValue is 1e18 GP: comfortably inside BIGINT while leaving headroom for tax maths.
 * - defaultBuyLimit 10_000 is deliberately not "unlimited"; items with no configured limit still
 *   cannot be cornered in one window.
 * - buyLimitWindowHours 4 is the OSRS window.
 * - maxFillsPerTransaction 10 keeps a settlement transaction short under contention.
 * - recoveryGraceSeconds 60 keeps login recovery from racing a second step still in flight.
 */
@Serializable
data class ExchangeConfig(
    val taxRateBps: Int = 200,
    val taxCapPerItem: Long = 5_000_000,
    val taxRateByCategoryBps: Map<String, Int> = emptyMap(),
    val maxActiveOrders: Int = 8,
    val maxQuantityPerOrder: Long = Int.MAX_VALUE.toLong(),
    val maxOrderValue: Long = 1_000_000_000_000_000_000L,
    val minLimitPrice: Long = 1,
    val defaultBuyLimit: Int = 10_000,
    val buyLimitWindowHours: Int = 4,
    val maxFillsPerTransaction: Int = 10,
    val expiryOptionsHours: List<Int> = listOf(1, 6, 24, 168),
    val sweepBatch: Int = 50,
    val expiryBatch: Int = 100,
    val recoveryGraceSeconds: Long = 60,
    val transactionRetries: Int = 3,
    val transactionBackoffMs: Long = 25,
    val pricing: PricingConfig = PricingConfig(),
    val health: HealthConfig = HealthConfig(),
    val liquidity: LiquidityConfig = LiquidityConfig(),
    val ops: OpsConfig = OpsConfig(),
    val seeding: SeedingConfig = SeedingConfig(),
) {
    fun rateBpsFor(category: String): Int = taxRateByCategoryBps[category] ?: taxRateBps

    val buyLimitWindow: Duration
        get() = Duration.ofHours(buyLimitWindowHours.toLong())

    val recoveryGrace: Duration
        get() = Duration.ofSeconds(recoveryGraceSeconds)

    fun validate(): ExchangeConfig {
        require(taxRateBps in 0..10_000) { "taxRateBps must be 0..10000" }
        require(taxCapPerItem >= 0) { "taxCapPerItem must be >= 0" }
        taxRateByCategoryBps.values.forEach { require(it in 0..10_000) { "category tax rate must be 0..10000" } }
        require(maxActiveOrders >= 1) { "maxActiveOrders must be >= 1" }
        require(maxQuantityPerOrder >= 1) { "maxQuantityPerOrder must be >= 1" }
        require(maxOrderValue >= 1) { "maxOrderValue must be >= 1" }
        require(minLimitPrice >= 1) { "minLimitPrice must be >= 1" }
        require(defaultBuyLimit >= 1) { "defaultBuyLimit must be >= 1" }
        require(buyLimitWindowHours >= 1) { "buyLimitWindowHours must be >= 1" }
        require(maxFillsPerTransaction >= 1) { "maxFillsPerTransaction must be >= 1" }
        require(expiryOptionsHours.all { it >= 1 }) { "expiry options must be >= 1 hour" }
        require(sweepBatch >= 1 && expiryBatch >= 1) { "batch sizes must be >= 1" }
        require(recoveryGraceSeconds >= 0) { "recoveryGraceSeconds must be >= 0" }
        require(transactionRetries >= 0 && transactionBackoffMs >= 0) { "retry settings must be >= 0" }
        pricing.validate()
        health.validate()
        liquidity.validate()
        ops.validate()
        seeding.validate()
        return this
    }

    companion object {
        val DEFAULT: ExchangeConfig = ExchangeConfig().validate()
    }
}

/**
 * Every knob of the Fluxious market price. Rates are basis points; curves are exponents.
 *
 * - tradeWindowHours 24: the trades a recalculation looks at.
 * - vwapHalfLifeHours 8: a trade eight hours old counts half as much as one just made.
 * - outlierBandBps 4000 (±40%) with an override when 10 trades from 5 accounts agree: one
 *   mad trade is noise, a crowd is a move.
 * - maxAccountWeightBps 2500: no single account is more than a quarter of the evidence.
 * - depthBandBps 1000 (±10%) and minOrderAgeMinutes 10: only patient, near-market orders
 *   count as supply or demand, which is what makes spoofing pointless.
 * - pressureSensitivity 0.05 and maxPressureBps 1000: a fully one-sided book moves the target
 *   5%, capped at 10%.
 * - confidenceFullDistinctTraders 20 / confidenceFullTrades 50: what "deep market" means for a
 *   small server; distinct traders weigh most.
 * - anchorMaxWeight 1.0 with anchorCurve 2: at zero confidence the target is the base price,
 *   at half confidence it is a quarter anchor.
 * - smoothing 0.5 and maxChange 5% → 20% by confidence: thin markets move slowly.
 * - discoveryMaxChangeBps 200 until confidence passes discoveryConfidenceThreshold 0.5.
 * - seededWeight 0.3 fading over seedMaxAgeDays 14, never counting for confidence.
 * - stableWindowHours 24: the median other systems may read.
 */
@Serializable
data class PricingConfig(
    val updateIntervalMinutes: Int = 60,
    val tradeWindowHours: Int = 24,
    val vwapHalfLifeHours: Double = 8.0,
    val outlierBandBps: Int = 4000,
    val outlierOverrideMinTrades: Int = 10,
    val outlierOverrideMinAccounts: Int = 5,
    val maxAccountWeightBps: Int = 2500,
    val depthBandBps: Int = 1000,
    val minOrderAgeMinutes: Int = 10,
    val pressureSensitivity: Double = 0.05,
    val maxPressureBps: Int = 1000,
    val confidenceFullDistinctTraders: Int = 20,
    val confidenceFullTrades: Int = 50,
    val confidenceRecencyHours: Double = 24.0,
    val anchorMaxWeight: Double = 1.0,
    val anchorCurve: Double = 2.0,
    val smoothing: Double = 0.5,
    val maxChangeLowBps: Int = 500,
    val maxChangeHighBps: Int = 2000,
    val discoveryMaxChangeBps: Int = 200,
    val discoveryConfidenceThreshold: Double = 0.5,
    val noTradeDriftBps: Int = 0,
    val seededWeight: Double = 0.3,
    val seedMaxAgeDays: Int = 14,
    val stableWindowHours: Int = 24,
) {
    fun validate(): PricingConfig {
        require(updateIntervalMinutes >= 1) { "updateIntervalMinutes must be >= 1" }
        require(tradeWindowHours >= 1 && stableWindowHours >= 1) { "windows must be >= 1 hour" }
        require(vwapHalfLifeHours > 0) { "vwapHalfLifeHours must be > 0" }
        require(outlierBandBps in 1..100_000) { "outlierBandBps out of range" }
        require(outlierOverrideMinTrades >= 1 && outlierOverrideMinAccounts >= 1) { "outlier override minimums must be >= 1" }
        require(maxAccountWeightBps in 1..10_000) { "maxAccountWeightBps must be 1..10000" }
        require(depthBandBps in 1..10_000) { "depthBandBps must be 1..10000" }
        require(minOrderAgeMinutes >= 0) { "minOrderAgeMinutes must be >= 0" }
        require(pressureSensitivity >= 0 && maxPressureBps in 0..10_000) { "pressure settings out of range" }
        require(confidenceFullDistinctTraders >= 1 && confidenceFullTrades >= 1) { "confidence fulls must be >= 1" }
        require(confidenceRecencyHours > 0) { "confidenceRecencyHours must be > 0" }
        require(anchorMaxWeight in 0.0..1.0 && anchorCurve > 0) { "anchor settings out of range" }
        require(smoothing in 0.0..1.0) { "smoothing must be 0..1" }
        require(maxChangeLowBps in 0..10_000 && maxChangeHighBps in maxChangeLowBps..10_000) { "maxChange must be 0..100% and low <= high" }
        require(discoveryMaxChangeBps in 0..10_000) { "discoveryMaxChangeBps must be 0..10000" }
        require(discoveryConfidenceThreshold in 0.0..1.0) { "discoveryConfidenceThreshold must be 0..1" }
        require(noTradeDriftBps in 0..10_000) { "noTradeDriftBps must be 0..10000" }
        require(seededWeight in 0.0..1.0 && seedMaxAgeDays >= 0) { "seed settings out of range" }
        return this
    }
}

/**
 * Detection, alerting and reconciliation thresholds (Parts H and J).
 *
 * - farFromMarketBps 3000: a pair trading 30% away from market is how GP moves between accounts
 *   through the exchange.
 * - repeatedPairingMinTrades 10 and repeatedPairingShareBps 5000: ten trades with the same
 *   counterparty that are half of everything you did.
 * - washMinCycleTrades 6: A→B→A round trips in one item inside the window.
 * - corneringShareBps 6000: one account holding 60% of an item's open buy depth or recent
 *   buy volume, above corneringMinValue GP so tiny markets don't trip it.
 * - rapidCancelPerHour 20: cancel/replace churn that spoofs the book.
 * - priceChange24hBps 2500 / priceChange7dBps 5000 / volumeChangeMultiple 3.0 /
 *   tradersChangeMultiple 3.0: what staff want to hear about per item, gated by
 *   alertMinVolume and alertMinDistinctTraders so two-trade markets stay quiet.
 * - exchangeWide*ChangeMultiple 2.5 and exchangeWideMinTraders 10: the same day-over-day
 *   comparison across the whole exchange, which is where a bot wave shows first.
 * - systemNetGpAlert 100M: net GP the system liquidity paid out in a day before someone looks.
 * - sinkFaucetRatio 3.0 above sinkFaucetMinGp 50M: a day where the game created three times the
 *   GP it destroyed, once there is enough of it to matter.
 */
@Serializable
data class HealthConfig(
    val detectionIntervalMinutes: Int = 30,
    val detectionWindowHours: Int = 24,
    val farFromMarketBps: Int = 3000,
    val repeatedPairingMinTrades: Int = 10,
    val repeatedPairingShareBps: Int = 5000,
    val washMinCycleTrades: Int = 6,
    val corneringShareBps: Int = 6000,
    val corneringMinValue: Long = 1_000_000,
    val rapidCancelPerHour: Int = 20,
    val alertIntervalMinutes: Int = 60,
    val priceChange24hBps: Int = 2500,
    val priceChange7dBps: Int = 5000,
    val volumeChangeMultiple: Double = 3.0,
    val tradersChangeMultiple: Double = 3.0,
    val alertMinVolume: Long = 100,
    val alertMinDistinctTraders: Int = 5,
    val exchangeWideVolumeChangeMultiple: Double = 2.5,
    val exchangeWideTradersChangeMultiple: Double = 2.5,
    val exchangeWideMinTraders: Int = 10,
    val systemNetGpAlert: Long = 100_000_000,
    val sinkFaucetRatio: Double = 3.0,
    val sinkFaucetMinGp: Long = 50_000_000,
    val reconciliationIntervalMinutes: Int = 60,
    val rollupIntervalMinutes: Int = 30,
    val snapshotHourUtc: Int = 0,
) {
    fun validate(): HealthConfig {
        require(detectionIntervalMinutes >= 1 && alertIntervalMinutes >= 1 && reconciliationIntervalMinutes >= 1 && rollupIntervalMinutes >= 1) {
            "health intervals must be >= 1 minute"
        }
        require(detectionWindowHours >= 1) { "detectionWindowHours must be >= 1" }
        require(farFromMarketBps in 1..100_000) { "farFromMarketBps out of range" }
        require(repeatedPairingMinTrades >= 2 && repeatedPairingShareBps in 1..10_000) { "repeated pairing settings out of range" }
        require(washMinCycleTrades >= 2) { "washMinCycleTrades must be >= 2" }
        require(corneringShareBps in 1..10_000 && corneringMinValue >= 0) { "cornering settings out of range" }
        require(rapidCancelPerHour >= 1) { "rapidCancelPerHour must be >= 1" }
        require(priceChange24hBps >= 1 && priceChange7dBps >= 1 && volumeChangeMultiple > 1.0 && tradersChangeMultiple > 1.0) { "alert thresholds out of range" }
        require(exchangeWideVolumeChangeMultiple > 1.0 && exchangeWideTradersChangeMultiple > 1.0 && exchangeWideMinTraders >= 0) { "exchange-wide alert thresholds out of range" }
        require(alertMinVolume >= 0 && alertMinDistinctTraders >= 0 && systemNetGpAlert >= 0) { "alert gates must be >= 0" }
        require(sinkFaucetRatio > 1.0 && sinkFaucetMinGp >= 0) { "sink/faucet settings out of range" }
        require(snapshotHourUtc in 0..23) { "snapshotHourUtc must be 0..23" }
        return this
    }
}

/**
 * System liquidity (Part I / Q3). Off unless [enabled] is true and [systemCharacterId] names a
 * real character; per-item settings live in exchange_system_liquidity.
 */
@Serializable
data class LiquidityConfig(
    val enabled: Boolean = false,
    val systemCharacterId: Int = 0,
    val intervalMinutes: Int = 60,
    val globalDailyGpCap: Long = 500_000_000,
    val globalDailyItemCap: Long = 1_000_000,
    val windDownCapShareBps: Int = 5000,
) {
    fun validate(): LiquidityConfig {
        require(intervalMinutes >= 1) { "liquidity intervalMinutes must be >= 1" }
        require(globalDailyGpCap >= 0 && globalDailyItemCap >= 0) { "liquidity caps must be >= 0" }
        require(windDownCapShareBps in 1..10_000) { "windDownCapShareBps must be 1..10000" }
        require(!enabled || systemCharacterId > 0) { "liquidity.systemCharacterId must be set when enabled" }
        return this
    }
}

/**
 * Operations, Part P: rate limits, probing flags, new-item launch window, metrics alerts and the
 * update shutdown window.
 *
 * - rateLimitOrdersPerMinute / rateLimitCancelsPerMinute 20: more than a human does by hand.
 * - probingFlagThreshold 20 invalid inputs / rateLimitFlagThreshold 10 trips: enough to be a
 *   pattern, not a fat finger.
 * - launchDefaultHours 168 and launchDefaultBuyLimit 100: one week of a small limit for new items.
 * - latencyP95AlertMs 1000 / retriesPerMinuteAlert 30: contention is usually the first symptom.
 * - shutdownIntakeCloseSeconds 20 / shutdownAllCloseSeconds 5: the spec's update window.
 */
@Serializable
data class OpsConfig(
    val rateLimitOrdersPerMinute: Int = 20,
    val rateLimitCancelsPerMinute: Int = 20,
    val probingFlagThreshold: Int = 20,
    val rateLimitFlagThreshold: Int = 10,
    val launchDefaultHours: Int = 168,
    val launchDefaultBuyLimit: Int = 100,
    val latencyP95AlertMs: Long = 1000,
    val retriesPerMinuteAlert: Long = 30,
    val metricsIntervalMinutes: Int = 1,
    val shutdownIntakeCloseSeconds: Int = 20,
    val shutdownAllCloseSeconds: Int = 5,
) {
    fun validate(): OpsConfig {
        require(rateLimitOrdersPerMinute >= 1 && rateLimitCancelsPerMinute >= 1) { "rate limits must be >= 1" }
        require(probingFlagThreshold >= 1 && rateLimitFlagThreshold >= 1) { "flag thresholds must be >= 1" }
        require(launchDefaultHours >= 1 && launchDefaultBuyLimit >= 1) { "launch defaults must be >= 1" }
        require(latencyP95AlertMs >= 1 && retriesPerMinuteAlert >= 0 && metricsIntervalMinutes >= 1) { "metrics settings out of range" }
        require(shutdownAllCloseSeconds in 0..shutdownIntakeCloseSeconds) { "shutdown window must be intake >= all >= 0" }
        return this
    }
}

/**
 * Launch seeding, Part Q2. Fabricated trades exist only so charts are not empty on day one, and
 * every default here is chosen to keep them harmless and reversible.
 *
 * - enabled true: seeding is available by default. Set it to false on the seeding config to
 *   close it off once launch is over; the caps below are what keep it harmless in the meantime.
 * - priceVarianceBps 800: trades scatter +/-8% around the base price, enough to look traded
 *   without inventing a trend.
 * - windowHours 72 spreads them over three days so hourly and daily buckets fill naturally.
 * - maxTradesPerItem 200 / maxTotalTrades 20000: hard caps, checked against what already exists.
 * - maxQuantityPerTrade 30 reflects a small launch population, not OSRS volumes.
 */
@Serializable
data class SeedingConfig(
    val enabled: Boolean = true,
    val priceVarianceBps: Int = 800,
    val windowHours: Int = 72,
    val maxTradesPerItem: Int = 200,
    val maxTotalTrades: Int = 20_000,
    val maxQuantityPerTrade: Int = 30,
) {
    fun validate(): SeedingConfig {
        require(priceVarianceBps in 0..9_000) { "priceVarianceBps must be 0..9000" }
        require(windowHours >= 1) { "windowHours must be >= 1" }
        require(maxTradesPerItem >= 1 && maxTotalTrades >= 1) { "seed caps must be >= 1" }
        require(maxQuantityPerTrade >= 1) { "maxQuantityPerTrade must be >= 1" }
        return this
    }
}

/** Loads exchange_config rows over the defaults. Unknown keys are ignored so old rows never break startup. */
object ExchangeConfigLoader {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Defaults are encoded with [encodeDefaults] on: without it kotlinx omits every property that
     * still equals its default, so the template we merge overrides into would be empty and every
     * override would be discarded as an unknown key.
     */
    private val defaultsJson = Json { encodeDefaults = true }

    fun load(dataSource: DataSource): ExchangeConfig =
        dataSource.connection.use { conn ->
            val overrides = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
            conn.prepareStatement(OpenRuneSql.text("central/exchange/config_load.sql")).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        overrides[rs.getString(1)] = json.parseToJsonElement(rs.getString(2))
                    }
                }
            }
            merge(overrides)
        }

    fun merge(overrides: Map<String, kotlinx.serialization.json.JsonElement>): ExchangeConfig {
        val defaults = defaultsJson.encodeToJsonElement(ExchangeConfig.DEFAULT).jsonObject
        val merged = JsonObject(defaults + overrides.filterKeys { it in defaults })
        return json.decodeFromJsonElement<ExchangeConfig>(merged).validate()
    }
}

/** Re-reads config at most once per [ttl]; both processes hold one of these. */
class ExchangeConfigProvider(
    private val dataSource: DataSource,
    private val ttl: Duration = Duration.ofSeconds(60),
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
) : () -> ExchangeConfig {
    @Volatile
    private var cached: ExchangeConfig = ExchangeConfig.DEFAULT

    @Volatile
    private var loadedAt: java.time.Instant = java.time.Instant.EPOCH

    override fun invoke(): ExchangeConfig {
        val now = clock.instant()
        if (Duration.between(loadedAt, now) >= ttl) {
            synchronized(this) {
                if (Duration.between(loadedAt, now) >= ttl) {
                    runCatching { ExchangeConfigLoader.load(dataSource) }.onSuccess { cached = it }
                    loadedAt = now
                }
            }
        }
        return cached
    }

    fun invalidate() {
        loadedAt = java.time.Instant.EPOCH
    }
}
