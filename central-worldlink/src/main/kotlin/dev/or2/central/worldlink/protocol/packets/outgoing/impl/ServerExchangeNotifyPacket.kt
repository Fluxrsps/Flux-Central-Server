package dev.or2.central.worldlink.protocol.packets.outgoing.impl

import dev.or2.central.worldlink.protocol.FieldKind
import dev.or2.central.worldlink.protocol.OutboundPacket
import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.central.worldlink.protocol.WorldPacketOutgoing
import dev.or2.central.worldlink.protocol.outboundFrame

/** A Trading Post notification for a character on the receiving world (fill, cancel, expiry). */
@WorldPacketOutgoing(
    opcode = WorldOpcodes.OP_SERVER_EXCHANGE_NOTIFY,
    name = "SERVER_EXCHANGE_NOTIFY",
    fields = [
        FieldKind.INT,
        FieldKind.LONG,
        FieldKind.STRING_96,
        FieldKind.LONG,
        FieldKind.INT,
        FieldKind.STRING_2048,
    ],
)
object ServerExchangeNotifyPacket : OutboundPacket<ServerExchangeNotifyPacket.Payload> {
    data class Payload(
        val characterId: Int,
        val notificationId: Long,
        val kind: String,
        val orderId: Long,
        val objId: Int,
        val payloadJson: String,
    )

    override fun encode(payload: Payload): ByteArray =
        outboundFrame(WorldOpcodes.OP_SERVER_EXCHANGE_NOTIFY) {
            writeInt(payload.characterId)
            writeLong(payload.notificationId)
            writeUtf8Truncated(payload.kind, 96)
            writeLong(payload.orderId)
            writeInt(payload.objId)
            writeUtf8Truncated(payload.payloadJson, 2048)
        }
}
