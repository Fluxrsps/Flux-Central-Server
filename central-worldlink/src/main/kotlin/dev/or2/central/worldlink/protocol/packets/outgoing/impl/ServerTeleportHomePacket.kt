package dev.or2.central.worldlink.protocol.packets.outgoing.impl

import dev.or2.central.worldlink.protocol.FieldKind
import dev.or2.central.worldlink.protocol.OutboundPacket
import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.central.worldlink.protocol.WorldPacketOutgoing
import dev.or2.central.worldlink.protocol.outboundFrame

@WorldPacketOutgoing(
    opcode = WorldOpcodes.OP_SERVER_TELEPORT_HOME,
    name = "SERVER_TELEPORT_HOME",
    fields = [FieldKind.LONG, FieldKind.INT],
)
object ServerTeleportHomePacket : OutboundPacket<ServerTeleportHomePacket.Payload> {
    data class Payload(val accountId: Long, val characterId: Int)

    override fun encode(payload: Payload): ByteArray =
        outboundFrame(WorldOpcodes.OP_SERVER_TELEPORT_HOME) {
            writeLong(payload.accountId)
            writeInt(payload.characterId)
        }
}
