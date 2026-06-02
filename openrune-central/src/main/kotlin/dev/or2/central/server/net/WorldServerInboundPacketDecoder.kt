package dev.or2.central.server.net

import dev.or2.central.server.net.codec.WorldServerInboundPacket
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageDecoder

/**
 * Turns one length-delimited [ByteBuf] into a heap [WorldServerInboundPacket] for the session handler
 * (same role as rsprot's login/game decoders sitting ahead of the channel handler).
 */
internal class WorldServerInboundPacketDecoder : MessageToMessageDecoder<ByteBuf>() {

    override fun decode(
        ctx: ChannelHandlerContext,
        msg: ByteBuf,
        out: MutableList<Any>,
    ) {
        val n = msg.readableBytes()
        val bytes = ByteArray(n)
        msg.readBytes(bytes)
        out.add(WorldServerInboundPacket(bytes))
    }
}
