package dev.or2.central.exchange.health

import dev.or2.central.exchange.ExchangeConfig
import dev.or2.central.exchange.ExchangeMetrics
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

class MetricsAlertService(
    private val config: () -> ExchangeConfig,
    private val alerts: AlertService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(MetricsAlertService::class.java)
    private var last: ExchangeMetrics.Snapshot? = null
    private var lastAt: Instant? = null

    fun run(): Int {
        val cfg = config().ops
        val now = clock.instant()
        val current = ExchangeMetrics.snapshot()
        val previous = last
        val previousAt = lastAt
        last = current
        lastAt = now
        if (previous == null || previousAt == null) return 0

        val minutes = Duration.between(previousAt, now).toMillis().coerceAtLeast(1) / 60_000.0
        val fills = current.fills - previous.fills
        val retries = current.retries - previous.retries
        val deadlocks = current.deadlocks - previous.deadlocks
        val l = current.latency
        if (cfg.verboseLogging) {
            log.info(
                "exchange metrics: created={} cancelled={} expired={} fills={} retries={} deadlocks={} match p50={}ms p95={}ms p99={}ms max={}ms",
                current.ordersCreated - previous.ordersCreated,
                current.ordersCancelled - previous.ordersCancelled,
                current.ordersExpired - previous.ordersExpired,
                fills, retries, deadlocks, l.p50, l.p95, l.p99, l.max,
            )
        }

        var raised = 0
        if (l.samples >= 20 && l.p95 > cfg.latencyP95AlertMs) {
            if (alerts.raise("SETTLEMENT_LATENCY", 0, AlertSeverity.WARN, """{"p50_ms":${l.p50},"p95_ms":${l.p95},"p99_ms":${l.p99},"max_ms":${l.max},"fills_per_min":${"%.1f".format(java.util.Locale.ROOT, fills / minutes)}}""")) raised++
        }
        val retriesPerMinute = retries / minutes
        if (cfg.retriesPerMinuteAlert > 0 && retriesPerMinute > cfg.retriesPerMinuteAlert) {
            if (alerts.raise("RETRY_RATE", 0, AlertSeverity.WARN, """{"retries_per_min":${"%.1f".format(java.util.Locale.ROOT, retriesPerMinute)},"deadlocks":$deadlocks}""")) raised++
        }
        return raised
    }
}
