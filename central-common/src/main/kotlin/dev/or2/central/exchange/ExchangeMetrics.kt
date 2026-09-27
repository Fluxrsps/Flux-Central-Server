package dev.or2.central.exchange

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide counters and a latency ring for the exchange, Part P2. Read by the metrics alert
 * job and the periodic log summary; reset never, deltas are the consumer's job.
 */
object ExchangeMetrics {
    val ordersCreated = AtomicLong()
    val ordersCancelled = AtomicLong()
    val ordersExpired = AtomicLong()
    val fills = AtomicLong()
    val retries = AtomicLong()
    val deadlocks = AtomicLong()
    val matchPasses = AtomicLong()

    private const val RING = 1024
    private val latencies = LongArray(RING)
    private val cursor = AtomicInteger()

    fun recordMatchPass(durationMs: Long) {
        matchPasses.incrementAndGet()
        latencies[Math.floorMod(cursor.getAndIncrement(), RING)] = durationMs
    }

    class Latency(val samples: Int, val p50: Long, val p95: Long, val p99: Long, val max: Long)

    fun latency(): Latency {
        val count = minOf(matchPasses.get(), RING.toLong()).toInt()
        if (count == 0) return Latency(0, 0, 0, 0, 0)
        val sorted = latencies.copyOf(count).sortedArray()
        fun pct(p: Double) = sorted[((count - 1) * p).toInt().coerceIn(0, count - 1)]
        return Latency(count, pct(0.50), pct(0.95), pct(0.99), sorted.last())
    }

    class Snapshot(
        val ordersCreated: Long,
        val ordersCancelled: Long,
        val ordersExpired: Long,
        val fills: Long,
        val retries: Long,
        val deadlocks: Long,
        val latency: Latency,
    )

    fun snapshot(): Snapshot =
        Snapshot(ordersCreated.get(), ordersCancelled.get(), ordersExpired.get(), fills.get(), retries.get(), deadlocks.get(), latency())
}
