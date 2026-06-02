package dev.or2.central.server.net

import dev.or2.central.server.net.ConnectionPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class WorldServerConnectionLimits(
    private val maxPerIp: Int,
    private val maxTotal: Int,
) {

    private val perIp = ConcurrentHashMap<String, AtomicInteger>()
    private val total = AtomicInteger()

    fun tryAcquire(remoteIp: String): ConnectionPermit? {

        // global limit
        if (maxTotal > 0) {
            val t = total.incrementAndGet()
            if (t > maxTotal) {
                total.decrementAndGet()
                return null
            }
        }

        // per-IP limit
        if (maxPerIp > 0) {
            val counter = perIp.computeIfAbsent(remoteIp) { AtomicInteger(0) }
            val ipCount = counter.incrementAndGet()

            if (ipCount > maxPerIp) {
                counter.decrementAndGet()
                total.decrementAndGet()
                return null
            }
        }

        // build permit (single source of truth for cleanup)
        return ConnectionPermit {
            if (maxTotal > 0) {
                total.decrementAndGet().coerceAtLeast(0)
            }

            if (maxPerIp > 0) {
                perIp.computeIfPresent(remoteIp) { _, v ->
                    val n = v.decrementAndGet().coerceAtLeast(0)
                    if (n == 0) null else v
                }
            }
        }
    }
}