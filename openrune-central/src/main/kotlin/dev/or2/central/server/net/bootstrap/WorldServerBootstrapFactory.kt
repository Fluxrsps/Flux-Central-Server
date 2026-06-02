package dev.or2.central.server.net.bootstrap

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.WriteBufferWaterMark
import io.netty.channel.socket.nio.NioServerSocketChannel

/**
 * Builds a [ServerBootstrap] for world-server TCP, similar in spirit to rsprot's
 * [net.rsprot.protocol.api.bootstrap.BootstrapFactory] (centralized socket options, allocator wiring).
 */
internal object WorldServerBootstrapFactory {

    fun createServerBootstrap(
        bossGroup: EventLoopGroup,
        workerGroup: EventLoopGroup,
        soBacklog: Int,
        allocator: ByteBufAllocator = PooledByteBufAllocator.DEFAULT,
    ): ServerBootstrap {
        val backlog = soBacklog.coerceIn(64, 16_384)
        return ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .option(ChannelOption.SO_BACKLOG, backlog)
            .option(ChannelOption.SO_REUSEADDR, true)
            .option(ChannelOption.ALLOCATOR, allocator)
            .childOption(ChannelOption.ALLOCATOR, allocator)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(
                ChannelOption.WRITE_BUFFER_WATER_MARK,
                WriteBufferWaterMark(32 * 1024, 64 * 1024),
            )
    }
}
