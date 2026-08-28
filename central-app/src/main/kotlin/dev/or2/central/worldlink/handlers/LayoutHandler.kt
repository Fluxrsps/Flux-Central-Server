package dev.or2.central.worldlink.handlers

import dev.or2.central.layout.LayoutService
import dev.or2.central.worldlink.WorldConnection
import dev.or2.central.worldlink.protocol.FrameReader
import dev.or2.central.worldlink.protocol.PacketDecodeException
import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.central.worldlink.protocol.layout.LayoutPackets
import dev.or2.central.worldlink.protocol.packets.incoming.impl.WorldLayoutListPacket
import dev.or2.central.worldlink.protocol.packets.incoming.impl.WorldLayoutLoadPacket
import dev.or2.central.worldlink.protocol.packets.incoming.impl.WorldLayoutSavePacket
import dev.or2.central.worldlink.protocol.packets.incoming.impl.WorldLayoutSharedPacket

/**
 * `toplevel_v2` layout frames.
 *
 * Authorized exactly as the social ones are: the world-link connection must be handshaken, and the
 * character id in the frame is taken on that connection's word. A world that has passed HELLO is
 * trusted to say which of its own players is asking.
 */
class LayoutHandler(
    private val layoutService: LayoutService,
) {
    fun handle(
        connection: WorldConnection,
        opcode: Int,
        input: FrameReader,
    ): HandlerResult {
        if (!connection.handshakeDone) {
            return notAllowed()
        }
        val reply =
            try {
                when (opcode) {
                    WorldOpcodes.OP_WORLD_LAYOUT_SAVE ->
                        layoutService.handleSave(WorldLayoutSavePacket.decode(input))
                    WorldOpcodes.OP_WORLD_LAYOUT_LOAD ->
                        layoutService.handleLoad(WorldLayoutLoadPacket.decode(input))
                    WorldOpcodes.OP_WORLD_LAYOUT_LIST ->
                        layoutService.handleList(WorldLayoutListPacket.decode(input).characterId)
                    WorldOpcodes.OP_WORLD_LAYOUT_SHARED ->
                        layoutService.handleShared(WorldLayoutSharedPacket.decode(input))
                    else -> return notAllowed()
                }
            } catch (_: PacketDecodeException) {
                return notAllowed()
            }

        return when (reply) {
            is LayoutService.LayoutReply.Ok -> HandlerResult.Reply(reply.frame)
            is LayoutService.LayoutReply.Fail -> HandlerResult.Reply(reply.frame)
        }
    }

    private fun notAllowed(): HandlerResult =
        HandlerResult.Reply(LayoutPackets.encodeFail(WorldOpcodes.LAYOUT_FAIL_NOT_ALLOWED))
}
