package dev.or2.central.worldlink.protocol.packets.outgoing.impl

import dev.or2.central.worldlink.protocol.FieldKind
import dev.or2.central.worldlink.protocol.OutboundPacket
import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.central.worldlink.protocol.WorldPacketOutgoing
import dev.or2.central.worldlink.protocol.outboundFrame

@WorldPacketOutgoing(
    opcode = WorldOpcodes.OP_SERVER_GAMEMODE_UPDATE,
    name = "SERVER_GAMEMODE_UPDATE",
    fields = [FieldKind.LONG, FieldKind.INT, FieldKind.STRING_96],
)
object ServerGamemodeUpdatePacket : OutboundPacket<ServerGamemodeUpdatePacket.Payload> {
    /** [gameMode] is the GameModes enum name (e.g. "HARDCORE_IRONMAN"). */
    data class Payload(val accountId: Long, val characterId: Int, val gameMode: String)

    override fun encode(payload: Payload): ByteArray =
        outboundFrame(WorldOpcodes.OP_SERVER_GAMEMODE_UPDATE) {
            writeLong(payload.accountId)
            writeInt(payload.characterId)
            writeUtf8Truncated(payload.gameMode, 96)
        }
}
