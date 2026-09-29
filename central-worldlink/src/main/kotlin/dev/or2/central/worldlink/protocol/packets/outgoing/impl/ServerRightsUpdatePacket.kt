package dev.or2.central.worldlink.protocol.packets.outgoing.impl

import dev.or2.central.worldlink.protocol.FieldKind
import dev.or2.central.worldlink.protocol.OutboundPacket
import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.central.worldlink.protocol.WorldPacketOutgoing
import dev.or2.central.worldlink.protocol.outboundFrame

@WorldPacketOutgoing(
    opcode = WorldOpcodes.OP_SERVER_RIGHTS_UPDATE,
    name = "SERVER_RIGHTS_UPDATE",
    fields = [FieldKind.LONG, FieldKind.STRING_96],
)
object ServerRightsUpdatePacket : OutboundPacket<ServerRightsUpdatePacket.Payload> {
    /** [rights] is the Rights enum name (e.g. "MODERATOR"), applies to every character on the account. */
    data class Payload(val accountId: Long, val rights: String)

    override fun encode(payload: Payload): ByteArray =
        outboundFrame(WorldOpcodes.OP_SERVER_RIGHTS_UPDATE) {
            writeLong(payload.accountId)
            writeUtf8Truncated(payload.rights, 96)
        }
}
