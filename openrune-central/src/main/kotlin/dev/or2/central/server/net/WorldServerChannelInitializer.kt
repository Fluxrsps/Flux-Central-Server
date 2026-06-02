package dev.or2.central.server.net

import WorldServerConnectionLimits
import dev.or2.central.WorldOperationRepository
import dev.or2.central.server.net.codec.WorldServerInboundFrameSpecs
import dev.or2.central.server.net.push.WorldServerPushChannelRegistry
import dev.or2.central.server.session.WorldServerSessionService
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.timeout.ReadTimeoutHandler
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Child channel pipeline for world ↔ central TCP (rsprot-style: initializer owns ordered handlers).
 *
 * Pipeline: connection gate → read timeout → **length framing** (TCP chunk) → optional rate limit →
 * **packet decode** (ByteBuf → [dev.or2.central.server.net.codec.WorldServerInboundPacket]) → session logic.
 */
internal class WorldServerChannelInitializer(
    private val sessionService: WorldServerSessionService,
    private val executor: ExecutorService,
    private val pushChannelRegistry: WorldServerPushChannelRegistry,
    private val worldOperationRepository: WorldOperationRepository,
    private val connectionLimits: WorldServerConnectionLimits,
    private val readTimeoutSeconds: Int,
    private val maxFramesPerSecond: Double,
    private val maxFrameBurst: Double,
) : ChannelInitializer<SocketChannel>() {

    override fun initChannel(ch: SocketChannel) {
        require(readTimeoutSeconds >= 5) { "readTimeoutSeconds must be >= 5" }

        val p = ch.pipeline()

        p.addLast(
            WorldServerHandlerNames.CONNECTION_GATE,
            WorldServerConnectionGateHandler(connectionLimits),
        )

        p.addLast(
            WorldServerHandlerNames.READ_TIMEOUT,
            ReadTimeoutHandler(readTimeoutSeconds.toLong(), TimeUnit.SECONDS),
        )

        p.addLast(
            WorldServerHandlerNames.LENGTH_FRAME_DECODER,
            LengthFieldBasedFrameDecoder(
                WorldServerInboundFrameSpecs.MAX_INBOUND_FRAMED_BODY,
                LENGTH_FIELD_OFFSET,
                LENGTH_FIELD_LENGTH,
                LENGTH_ADJUSTMENT,
                INITIAL_BYTES_TO_STRIP,
            ),
        )

        p.addLast(
            WorldServerHandlerNames.LENGTH_FRAME_ENCODER,
            LengthFieldPrepender(LENGTH_FIELD_LENGTH),
        )

        if (maxFramesPerSecond > 0.0) {
            p.addLast(
                WorldServerHandlerNames.INBOUND_RATE_LIMIT,
                WorldServerInboundRateLimiterHandler(maxFramesPerSecond, maxFrameBurst),
            )
        }

        p.addLast(
            WorldServerHandlerNames.INBOUND_PACKET_DECODER,
            WorldServerInboundPacketDecoder(),
        )

        p.addLast(
            WorldServerHandlerNames.SESSION,
            WorldServerSessionChannelHandler(
                sessionService,
                executor,
                pushChannelRegistry,
                worldOperationRepository,
            ),
        )
    }

    private companion object {
        private const val LENGTH_FIELD_OFFSET = 0
        private const val LENGTH_FIELD_LENGTH = 4
        private const val LENGTH_ADJUSTMENT = 0
        private const val INITIAL_BYTES_TO_STRIP = 4
    }
}
