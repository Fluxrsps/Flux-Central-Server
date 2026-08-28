package dev.or2.central.worldlink.protocol.packets.incoming.impl

import dev.or2.central.worldlink.protocol.FieldKind
import dev.or2.central.worldlink.protocol.FrameReader
import dev.or2.central.worldlink.protocol.InboundPacket
import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.central.worldlink.protocol.WorldPacketIncoming
import dev.or2.central.worldlink.protocol.layout.LayoutCharacterPayload
import dev.or2.central.worldlink.protocol.layout.LayoutPackets
import dev.or2.central.worldlink.protocol.layout.LayoutSavePayload
import dev.or2.central.worldlink.protocol.layout.LayoutSharedPayload
import dev.or2.central.worldlink.protocol.layout.LayoutSlotPayload

@WorldPacketIncoming(
    opcode = WorldOpcodes.OP_WORLD_LAYOUT_SAVE,
    name = "WORLD_LAYOUT_SAVE",
    fields = [
        // character id
        FieldKind.INT,
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
object WorldLayoutSavePacket : InboundPacket<LayoutSavePayload> {
    override fun decode(input: FrameReader): LayoutSavePayload = LayoutPackets.decodeSave(input)
}

@WorldPacketIncoming(
    opcode = WorldOpcodes.OP_WORLD_LAYOUT_LOAD,
    name = "WORLD_LAYOUT_LOAD",
    fields = [FieldKind.INT, FieldKind.BYTE],
)
object WorldLayoutLoadPacket : InboundPacket<LayoutSlotPayload> {
    override fun decode(input: FrameReader): LayoutSlotPayload = LayoutPackets.decodeLoad(input)
}

@WorldPacketIncoming(
    opcode = WorldOpcodes.OP_WORLD_LAYOUT_LIST,
    name = "WORLD_LAYOUT_LIST",
    fields = [FieldKind.INT],
)
object WorldLayoutListPacket : InboundPacket<LayoutCharacterPayload> {
    override fun decode(input: FrameReader): LayoutCharacterPayload = LayoutPackets.decodeList(input)
}

@WorldPacketIncoming(
    opcode = WorldOpcodes.OP_WORLD_LAYOUT_SHARED,
    name = "WORLD_LAYOUT_SHARED",
    fields = [FieldKind.INT, FieldKind.STRING_LAYOUT_OWNER, FieldKind.BYTE],
)
object WorldLayoutSharedPacket : InboundPacket<LayoutSharedPayload> {
    override fun decode(input: FrameReader): LayoutSharedPayload = LayoutPackets.decodeShared(input)
}
