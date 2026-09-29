package dev.or2.central.worldlink.protocol.packets.outgoing.impl

import dev.or2.central.worldlink.protocol.FieldKind
import dev.or2.central.worldlink.protocol.OutboundPacket
import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.central.worldlink.protocol.WorldPacketOutgoing
import dev.or2.central.worldlink.protocol.outboundFrame

@WorldPacketOutgoing(
    opcode = WorldOpcodes.OP_SERVER_DONATOR_UPDATE,
    name = "SERVER_DONATOR_UPDATE",
    fields = [FieldKind.LONG, FieldKind.INT, FieldKind.STRING_96],
)
object ServerDonatorUpdatePacket : OutboundPacket<ServerDonatorUpdatePacket.Payload> {
    /** [donatorRank] is the DonatorRanks enum name (e.g. "ONYX"). */
    data class Payload(val accountId: Long, val characterId: Int, val donatorRank: String)

    override fun encode(payload: Payload): ByteArray =
        outboundFrame(WorldOpcodes.OP_SERVER_DONATOR_UPDATE) {
            writeLong(payload.accountId)
            writeInt(payload.characterId)
            writeUtf8Truncated(payload.donatorRank, 96)
        }
}
