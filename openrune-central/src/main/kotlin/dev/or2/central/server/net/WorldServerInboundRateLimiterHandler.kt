package dev.or2.central.server.net

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter

/**
 * Per-channel token bucket on **framed** [ByteBuf] traffic (after length framing, before packet decode).
 */
internal class WorldServerInboundRateLimiterHandler(
    maxPacketsPerSecond: Double,
    maxBurst: Double,
) : ChannelInboundHandlerAdapter() {

    private val bucket: FrameTokenBucket? =
        if (maxPacketsPerSecond > 0.0) {
            FrameTokenBucket(maxPacketsPerSecond, maxBurst.coerceAtLeast(1.0))
        } else {
            null
        }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is ByteBuf) {
            ctx.fireChannelRead(msg)
            return
        }
        if (bucket?.tryConsume() == false) {
            msg.release()
            ctx.close()
            return
        }
        ctx.fireChannelRead(msg)
    }

    private class FrameTokenBucket(
        private val refillPerSecond: Double,
        private val maxBurst: Double,
    ) {
        private var tokens = maxBurst
        private var lastNanos = System.nanoTime()

        fun tryConsume(): Boolean {
            val now = System.nanoTime()
            val elapsed = (now - lastNanos) / 1_000_000_000.0
            lastNanos = now

            tokens = minOf(maxBurst, tokens + elapsed * refillPerSecond)

            if (tokens < 1.0) return false
            tokens -= 1.0
            return true
        }
    }
}
