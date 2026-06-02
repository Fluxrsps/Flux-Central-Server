package dev.or2.central.server.net

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import java.net.InetSocketAddress

internal class WorldServerConnectionGateHandler(
    private val limits: WorldServerConnectionLimits,
) : ChannelInboundHandlerAdapter() {

    private var permit: ConnectionPermit? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        val addr = ctx.channel().remoteAddress() as? InetSocketAddress
        val ip = addr?.address?.hostAddress ?: "unknown"

        val p = limits.tryAcquire(ip)
        if (p == null) {
            ctx.close()
            return
        }

        permit = p
        super.channelActive(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        permit?.release()
        permit = null
        super.channelInactive(ctx)
    }
}