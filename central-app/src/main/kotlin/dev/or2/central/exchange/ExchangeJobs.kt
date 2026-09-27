package dev.or2.central.exchange

import dev.or2.central.exchange.health.DetectionService
import dev.or2.central.exchange.health.EconomySnapshotService
import dev.or2.central.exchange.health.MarketAlertService
import dev.or2.central.exchange.health.MetricsAlertService
import dev.or2.central.exchange.health.ReconciliationService
import dev.or2.central.exchange.health.SinkFaucetService
import dev.or2.central.exchange.health.StatsRollupService
import dev.or2.central.exchange.health.SystemLiquidityService
import dev.or2.central.exchange.pricing.MarketPriceService
import org.slf4j.LoggerFactory
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The always-on half of the Trading Post. Every job runs on Central's shared scheduler, skips a
 * tick rather than overlap itself, and logs rather than throws.
 */
class ExchangeJobs(
    private val engine: ExchangeEngine,
    private val pricing: MarketPriceService,
    private val detection: DetectionService,
    private val marketAlerts: MarketAlertService,
    private val reconciliation: ReconciliationService,
    private val rollup: StatsRollupService,
    private val snapshots: EconomySnapshotService,
    private val liquidity: SystemLiquidityService,
    private val sinkFaucet: SinkFaucetService,
    private val metrics: MetricsAlertService,
    private val config: () -> ExchangeConfig,
    private val scheduler: ScheduledExecutorService,
    private val sweepIntervalMs: Long = 2_000,
    private val expiryIntervalSeconds: Long = 30,
) {
    private val log = LoggerFactory.getLogger(ExchangeJobs::class.java)
    private val running = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()

    fun start() {
        val cfg = config()
        scheduler.scheduleAtFixedRate({ guarded("sweep", ::sweepOnce) }, sweepIntervalMs, sweepIntervalMs, TimeUnit.MILLISECONDS)
        scheduler.scheduleAtFixedRate({ guarded("expiry", ::expireOnce) }, expiryIntervalSeconds, expiryIntervalSeconds, TimeUnit.SECONDS)
        every("market price", cfg.pricing.updateIntervalMinutes, 1, ::repriceOnce)
        every("rollup", cfg.health.rollupIntervalMinutes, 2, ::rollupOnce)
        every("detection", cfg.health.detectionIntervalMinutes, 3, ::detectOnce)
        every("market alerts", cfg.health.alertIntervalMinutes, 4, ::marketAlertsOnce)
        every("reconciliation", cfg.health.reconciliationIntervalMinutes, 5, ::reconcileOnce)
        every("snapshot", 60, 6, ::snapshotOnce)
        every("system liquidity", cfg.liquidity.intervalMinutes, 7, ::liquidityOnce)
        every("sink/faucet", cfg.health.alertIntervalMinutes, 8, ::sinkFaucetOnce)
        every("metrics", cfg.ops.metricsIntervalMinutes, 1, ::metricsOnce)
    }

    fun metricsOnce() {
        metrics.run()
    }

    fun sinkFaucetOnce() {
        if (sinkFaucet.run()) log.warn("exchange raised a sink/faucet imbalance alert")
    }

    private fun every(name: String, minutes: Int, initialDelayMinutes: Long, block: () -> Unit) {
        val period = minutes.toLong().coerceAtLeast(1)
        scheduler.scheduleAtFixedRate({ guarded(name, block) }, initialDelayMinutes, period, TimeUnit.MINUTES)
    }

    fun sweepOnce() {
        val fills = engine.sweep()
        if (fills > 0) log.info("exchange sweep made {} fills", fills)
    }

    fun expireOnce() {
        val expired = engine.expireDue()
        if (expired > 0) log.info("exchange expired {} orders", expired)
    }

    fun repriceOnce() {
        val s = pricing.recomputeAll()
        log.info("market prices: {} items, {} changed, {} skipped, {} failed", s.processed, s.changed, s.skipped, s.failed)
    }

    fun rollupOnce() {
        val days = rollup.run()
        if (days > 0) log.info("exchange rollup rebuilt {} item-days", days)
    }

    fun detectOnce() {
        val s = detection.run()
        if (s.total > 0) log.info("exchange detection flagged {} (far={}, pairing={}, wash={}, corner={}, cancel={})", s.total, s.farFromMarket, s.repeatedPairing, s.washTrading, s.cornering, s.rapidCancel)
    }

    fun marketAlertsOnce() {
        val raised = marketAlerts.run()
        if (raised > 0) log.info("exchange raised {} market alerts", raised)
    }

    fun reconcileOnce() {
        val report = reconciliation.run()
        if (!report.clean) log.error("exchange reconciliation found {} mismatches", report.mismatches.size)
    }

    fun snapshotOnce() {
        snapshots.snapshot()
    }

    fun liquidityOnce() {
        val s = liquidity.run()
        if (s.items > 0) log.info("system liquidity: {} items, {} orders placed, {} skipped, {} wound down", s.items, s.placed, s.skipped, s.disabled)
    }

    private fun guarded(name: String, block: () -> Unit) {
        val flag = running.getOrPut(name) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) return
        try {
            block()
        } catch (e: Exception) {
            log.warn("exchange {} job failed", name, e)
        } finally {
            flag.set(false)
        }
    }
}
