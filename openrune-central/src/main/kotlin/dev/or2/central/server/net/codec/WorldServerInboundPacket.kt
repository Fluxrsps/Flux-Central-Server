package dev.or2.central.server.net.codec

/**
 * One **application packet** from the game server after [io.netty.handler.codec.LengthFieldBasedFrameDecoder]:
 * a single length-delimited TCP chunk whose bytes are `opcode` followed by the opcode-specific body.
 *
 * This is not the 4-byte length prefix (Netty strips that); it is the payload the central session layer parses.
 */
data class WorldServerInboundPacket(
    val content: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WorldServerInboundPacket
        return content.contentEquals(other.content)
    }

    override fun hashCode(): Int = content.contentHashCode()
}
