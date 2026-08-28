package dev.or2.central.worldlink.protocol.packets.outgoing.impl

import dev.or2.central.worldlink.protocol.FieldKind
import dev.or2.central.worldlink.protocol.FrameReader
import dev.or2.central.worldlink.protocol.OutboundPacket
import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.central.worldlink.protocol.WorldPacketOutgoing
import dev.or2.central.worldlink.protocol.layout.LayoutListSnapshot
import dev.or2.central.worldlink.protocol.layout.LayoutPackets
import dev.or2.central.worldlink.protocol.layout.LayoutSnapshot

@WorldPacketOutgoing(
    opcode = WorldOpcodes.OP_WORLD_LAYOUT_OK,
    name = "WORLD_LAYOUT_OK",
    allowedBodyBytes = [0],
)
object WorldLayoutOkPacket : OutboundPacket<Unit> {
    override fun encode(payload: Unit): ByteArray = LayoutPackets.encodeOk()
}

@WorldPacketOutgoing(
    opcode = WorldOpcodes.OP_WORLD_LAYOUT_FAIL,
    name = "WORLD_LAYOUT_FAIL",
    fields = [FieldKind.BYTE],
)
object WorldLayoutFailPacket : OutboundPacket<WorldLayoutFailPacket.Payload> {
    data class Payload(val reason: Int)

    override fun encode(payload: Payload): ByteArray = LayoutPackets.encodeFail(payload.reason)
}

@WorldPacketOutgoing(
    opcode = WorldOpcodes.OP_WORLD_LAYOUT_DATA,
    name = "WORLD_LAYOUT_DATA",
    fields = [
        // slot
        FieldKind.BYTE,
        // name key
        FieldKind.INT,
        // shared
        FieldKind.BYTE,
        // screen width / height
        FieldKind.INT,
        FieldKind.INT,
        FieldKind.LAYOUT_WINDOW_LIST,
        FieldKind.LAYOUT_DISPLAY_LIST,
    ],
)
object WorldLayoutDataPacket : OutboundPacket<LayoutSnapshot> {
    override fun encode(payload: LayoutSnapshot): ByteArray = LayoutPackets.encodeData(payload)

    fun decode(input: FrameReader): LayoutSnapshot = LayoutPackets.decodeData(input)
}

@WorldPacketOutgoing(
    opcode = WorldOpcodes.OP_WORLD_LAYOUT_LIST_OK,
    name = "WORLD_LAYOUT_LIST_OK",
    fields = [
        // whether the character shares their layouts
        FieldKind.BYTE,
        FieldKind.LAYOUT_SUMMARY_LIST,
    ],
)
object WorldLayoutListOkPacket : OutboundPacket<LayoutListSnapshot> {
    override fun encode(payload: LayoutListSnapshot): ByteArray = LayoutPackets.encodeListOk(payload)

    fun decode(input: FrameReader): LayoutListSnapshot = LayoutPackets.decodeListOk(input)
}
