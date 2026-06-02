package dev.or2.central.server.net

import dev.or2.central.WorldOperationRepository
import dev.or2.central.server.net.codec.WorldServerInboundPacket
import dev.or2.central.server.net.codec.writeServerRebootSchedule
import dev.or2.central.server.net.push.WorldServerPushChannelRegistry
import dev.or2.central.server.session.WorldServerConnectionState
import dev.or2.central.server.session.WorldServerHandleResult
import dev.or2.central.server.session.WorldServerSessionService
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.*
import io.netty.handler.timeout.ReadTimeoutException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.ExecutorService
import org.slf4j.LoggerFactory

/**
 * Application handler for decoded [WorldServerInboundPacket]s — analogous to rsprot's
 * [net.rsprot.protocol.api.login.LoginChannelHandler] consuming decoded messages instead of raw bytes.
 */
internal class WorldServerSessionChannelHandler(
    private val sessionService: WorldServerSessionService,
    private val executor: ExecutorService,
    private val pushChannelRegistry: WorldServerPushChannelRegistry,
    private val worldOperationRepository: WorldOperationRepository,
) : SimpleChannelInboundHandler<WorldServerInboundPacket>() {

    private val log = LoggerFactory.getLogger(WorldServerSessionChannelHandler::class.java)

    private val state = WorldServerConnectionState()
    private var registeredWorldId: Int? = null

    override fun channelRead0(ctx: ChannelHandlerContext, msg: WorldServerInboundPacket) {
        state.remoteHost =
            (ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress

        executor.execute {
            try {
                val result = sessionService.handle(state, msg)

                handleResult(ctx, result)
            } catch (e: Exception) {
                log.error("World server handler error (peer={})", ctx.channel().remoteAddress(), e)
                ctx.close()
            }
        }
    }

    private fun handleResult(
        ctx: ChannelHandlerContext,
        result: WorldServerHandleResult,
    ) {
        when (result) {

            is WorldServerHandleResult.Reply -> {
                val deferPushRegistration = state.subscribedForServerPush
                val buf = Unpooled.wrappedBuffer(result.payload)

                ctx.writeAndFlush(buf).addListener { cf ->
                    if (!cf.isSuccess) return@addListener
                    if (deferPushRegistration) {
                        runAfterPushSubscribeAck(ctx)
                    }
                    if (result.closeAfterWrite) {
                        ctx.close()
                    }
                }
            }

            WorldServerHandleResult.CloseSilent -> {
                ctx.close()
            }
        }
    }

    /**
     * Registers the world push channel only after the subscribe-ack has been flushed,
     * then re-sends active reboot schedules from the DB (world was offline during NOTIFY).
     */
    private fun runAfterPushSubscribeAck(ctx: ChannelHandlerContext) {
        if (registeredWorldId != null) return
        if (!state.handshakeDone || !state.subscribedForServerPush) return
        if (state.worldId < 0) return

        registeredWorldId = state.worldId
        state.subscribedForServerPush = false

        val worldId = state.worldId
        val ch = ctx.channel()
        pushChannelRegistry.register(worldId, ch)

        executor.execute {
            val rows =
                try {
                    worldOperationRepository.listActiveRebootPushesForWorld(worldId)
                } catch (e: Exception) {
                    log.warn("Active reboot resync query failed (worldId={})", worldId, e)
                    emptyList()
                }
            if (rows.isEmpty()) return@execute

            ch.eventLoop().execute {
                if (!ch.isActive || registeredWorldId != worldId) return@execute
                try {
                    for (row in rows) {
                        val frame =
                            writeServerRebootSchedule(
                                clear = false,
                                worldScope = row.scopeWorldId,
                                rebootAtMs = row.rebootAtEpochMillis,
                                message = row.message,
                            )
                        ch.write(Unpooled.wrappedBuffer(frame))
                    }
                    ch.flush()
                } catch (e: Exception) {
                    log.warn("Active reboot resync write failed (worldId={})", worldId, e)
                }
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        when {
            cause.isBenignWorldDisconnect() ->
                log.debug("World server peer disconnected ({})", ctx.channel().remoteAddress())

            cause is ReadTimeoutException ->
                log.debug("World server read timeout, closing {}", ctx.channel().remoteAddress())

            cause is TooLongFrameException ||
                cause is CorruptedFrameException ||
                cause is DecoderException ->
                log.debug("World framing error from {}: {}", ctx.channel().remoteAddress(), cause.toString())

            else ->
                log.warn("World server channel exception", cause)
        }
        ctx.close()
    }

    private fun Throwable.isBenignWorldDisconnect(): Boolean {
        var t: Throwable? = this

        while (t != null) {
            when {
                t is ClosedChannelException -> return true

                t is SocketException -> {
                    val msg = t.message?.lowercase().orEmpty()
                    if ("connection reset" in msg ||
                        "broken pipe" in msg ||
                        "connection abort" in msg
                    ) return true
                }

                t is IOException -> {
                    val msg = t.message?.lowercase().orEmpty()
                    if ("connection reset" in msg ||
                        "broken pipe" in msg
                    ) return true
                }
            }
            t = t.cause
        }

        return false
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        registeredWorldId?.let {
            pushChannelRegistry.unregister(it, ctx.channel())
        }
        registeredWorldId = null
        super.channelInactive(ctx)
    }
}
