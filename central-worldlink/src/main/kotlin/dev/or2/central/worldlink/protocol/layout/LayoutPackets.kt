package dev.or2.central.worldlink.protocol.layout

import dev.or2.central.worldlink.protocol.FrameReader
import dev.or2.central.worldlink.protocol.FrameWriter
import dev.or2.central.worldlink.protocol.PacketDecodeException
import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.central.worldlink.protocol.outboundFrame
import dev.or2.central.worldlink.protocol.utf8TruncatedTo

/**
 * `toplevel_v2` layout world-link codec.
 *
 * World → Central layout frames are authorized the same way the social ones are: by the
 * authenticated world-link connection (HELLO + world key) plus a character id, with no per-player
 * session token.
 *
 * Nothing here interprets a rect. Central is storage for these: the frame decides what a coordinate
 * means, and a Central that had an opinion about it would be a second place to keep in step.
 */
object LayoutPackets {
    fun encodeSave(payload: LayoutSavePayload): ByteArray =
        outboundFrame(WorldOpcodes.OP_WORLD_LAYOUT_SAVE) {
            writeInt(payload.characterId)
            writeSnapshotBody(payload.snapshot)
        }

    fun encodeLoad(payload: LayoutSlotPayload): ByteArray =
        outboundFrame(WorldOpcodes.OP_WORLD_LAYOUT_LOAD) {
            writeInt(payload.characterId)
            writeByte(payload.slot)
        }

    fun encodeList(characterId: Int): ByteArray =
        outboundFrame(WorldOpcodes.OP_WORLD_LAYOUT_LIST) {
            writeInt(characterId)
        }

    fun encodeShared(payload: LayoutSharedPayload): ByteArray =
        outboundFrame(WorldOpcodes.OP_WORLD_LAYOUT_SHARED) {
            writeInt(payload.viewerCharacterId)
            writeUtf8Truncated(payload.ownerName, LayoutLimits.OWNER_NAME_MAX_UTF8)
            writeByte(payload.slot)
        }

    fun encodeOk(): ByteArray = byteArrayOf(WorldOpcodes.OP_WORLD_LAYOUT_OK.toByte())

    fun encodeFail(reason: Int): ByteArray =
        byteArrayOf(WorldOpcodes.OP_WORLD_LAYOUT_FAIL.toByte(), reason.toByte())

    fun encodeData(snapshot: LayoutSnapshot): ByteArray =
        outboundFrame(WorldOpcodes.OP_WORLD_LAYOUT_DATA) {
            writeSnapshotBody(snapshot)
        }

    fun encodeListOk(snapshot: LayoutListSnapshot): ByteArray =
        outboundFrame(WorldOpcodes.OP_WORLD_LAYOUT_LIST_OK) {
            writeByte(if (snapshot.shared) 1 else 0)
            writeShort(snapshot.slots.size)
            for (slot in snapshot.slots) {
                writeByte(slot.slot)
                writeInt(slot.nameKey)
                writeInt(slot.screenWidth)
                writeInt(slot.screenHeight)
            }
        }

    fun decodeSave(input: FrameReader): LayoutSavePayload {
        val characterId = input.readInt()
        val snapshot = input.readSnapshotBody()
        input.requireFullyConsumed()
        return LayoutSavePayload(characterId, snapshot)
    }

    fun decodeLoad(input: FrameReader): LayoutSlotPayload {
        val characterId = input.readInt()
        val slot = input.readUnsignedByte()
        input.requireFullyConsumed()
        return LayoutSlotPayload(characterId, slot)
    }

    fun decodeList(input: FrameReader): LayoutCharacterPayload {
        val characterId = input.readInt()
        input.requireFullyConsumed()
        return LayoutCharacterPayload(characterId)
    }

    fun decodeShared(input: FrameReader): LayoutSharedPayload {
        val viewerCharacterId = input.readInt()
        val ownerName = input.readUtf8LenPrefixed().trim()
        val slot = input.readUnsignedByte()
        input.requireFullyConsumed()
        return LayoutSharedPayload(viewerCharacterId, ownerName, slot)
    }

    fun decodeData(input: FrameReader): LayoutSnapshot {
        val snapshot = input.readSnapshotBody()
        input.requireFullyConsumed()
        return snapshot
    }

    fun decodeListOk(input: FrameReader): LayoutListSnapshot {
        val shared = input.readUnsignedByte() != 0
        val count = input.readUnsignedShort()
        if (count > LayoutLimits.MAX_SLOTS) {
            throw PacketDecodeException("layout list has $count slots, max ${LayoutLimits.MAX_SLOTS}")
        }
        val slots = ArrayList<LayoutSummary>(count)
        repeat(count) {
            slots +=
                LayoutSummary(
                    slot = input.readUnsignedByte(),
                    nameKey = input.readInt(),
                    screenWidth = input.readInt(),
                    screenHeight = input.readInt(),
                )
        }
        input.requireFullyConsumed()
        return LayoutListSnapshot(shared, slots)
    }

    /**
     * The layout itself, shared by SAVE (which prefixes a character id) and DATA (which does not).
     *
     * Written as one block rather than two similar ones so a field added to a layout cannot end up
     * on only one side of the round trip.
     */
    private fun FrameWriter.writeSnapshotBody(snapshot: LayoutSnapshot) {
        writeByte(snapshot.slot)
        writeInt(snapshot.nameKey)
        writeByte(if (snapshot.shared) 1 else 0)
        writeInt(snapshot.screenWidth)
        writeInt(snapshot.screenHeight)
        writeShort(snapshot.windows.size)
        for (window in snapshot.windows) {
            writeInt(window.panel)
            writeInt(window.x)
            writeInt(window.y)
            writeInt(window.width)
            writeInt(window.height)
            writeInt(window.hostPanel)
            writeInt(window.tabOrder)
        }
        writeShort(snapshot.displays.size)
        for (display in snapshot.displays) {
            writeInt(display.display)
            writeInt(display.x)
            writeInt(display.y)
            writeInt(display.width)
            writeInt(display.height)
        }
    }

    private fun FrameReader.readSnapshotBody(): LayoutSnapshot {
        val slot = readUnsignedByte()
        val nameKey = readInt()
        val shared = readUnsignedByte() != 0
        val screenWidth = readInt()
        val screenHeight = readInt()

        val windowCount = readUnsignedShort()
        if (windowCount > LayoutLimits.MAX_WINDOWS) {
            throw PacketDecodeException("layout has $windowCount windows, max ${LayoutLimits.MAX_WINDOWS}")
        }
        val windows = ArrayList<LayoutWindow>(windowCount)
        repeat(windowCount) {
            windows +=
                LayoutWindow(
                    panel = readInt(),
                    x = readInt(),
                    y = readInt(),
                    width = readInt(),
                    height = readInt(),
                    hostPanel = readInt(),
                    tabOrder = readInt(),
                )
        }

        val displayCount = readUnsignedShort()
        if (displayCount > LayoutLimits.MAX_DISPLAYS) {
            throw PacketDecodeException("layout has $displayCount displays, max ${LayoutLimits.MAX_DISPLAYS}")
        }
        val displays = ArrayList<LayoutDisplay>(displayCount)
        repeat(displayCount) {
            displays +=
                LayoutDisplay(
                    display = readInt(),
                    x = readInt(),
                    y = readInt(),
                    width = readInt(),
                    height = readInt(),
                )
        }

        return LayoutSnapshot(slot, nameKey, shared, screenWidth, screenHeight, windows, displays)
    }

    private fun FrameWriter.writeUtf8Truncated(
        value: String,
        maxBytes: Int,
    ) {
        val utf8 = utf8TruncatedTo(value, maxBytes)
        writeShort(utf8.size)
        writeBytes(utf8)
    }
}

/** Game → Central layout frames (alongside [dev.or2.central.worldlink.protocol.GameToCentralPackets]). */
object GameToCentralLayoutPackets {
    fun layoutSave(
        characterId: Int,
        snapshot: LayoutSnapshot,
    ): ByteArray = LayoutPackets.encodeSave(LayoutSavePayload(characterId, snapshot))

    fun layoutLoad(
        characterId: Int,
        slot: Int,
    ): ByteArray = LayoutPackets.encodeLoad(LayoutSlotPayload(characterId, slot))

    fun layoutList(characterId: Int): ByteArray = LayoutPackets.encodeList(characterId)

    fun layoutShared(
        viewerCharacterId: Int,
        ownerName: String,
        slot: Int,
    ): ByteArray = LayoutPackets.encodeShared(LayoutSharedPayload(viewerCharacterId, ownerName, slot))
}
