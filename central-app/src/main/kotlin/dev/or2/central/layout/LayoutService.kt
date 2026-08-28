package dev.or2.central.layout

import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.central.worldlink.protocol.layout.LayoutDisplay
import dev.or2.central.worldlink.protocol.layout.LayoutLimits
import dev.or2.central.worldlink.protocol.layout.LayoutListSnapshot
import dev.or2.central.worldlink.protocol.layout.LayoutPackets
import dev.or2.central.worldlink.protocol.layout.LayoutSavePayload
import dev.or2.central.worldlink.protocol.layout.LayoutSharedPayload
import dev.or2.central.worldlink.protocol.layout.LayoutSlotPayload
import dev.or2.central.worldlink.protocol.layout.LayoutSnapshot
import dev.or2.central.worldlink.protocol.layout.LayoutSummary
import dev.or2.central.worldlink.protocol.layout.LayoutWindow
import org.slf4j.LoggerFactory

/**
 * The layout side of the world link: save a slot, read one back, and list what a character has.
 *
 * Central stores layouts and decides who may see one. It has no opinion about what is in them — a
 * panel id and a rect mean something to the frame and nothing here, and a Central that checked them
 * would be a second place that has to be kept in step with the cache.
 *
 * The one judgement it does make is [handleShared]: a layout belonging to somebody else is only
 * handed over when its owner has ticked "let others use my layouts".
 *
 * Which layout a character is currently in is not Central's business at all. Only the world they are
 * logged into ever reads it, so it lives in a varbit on the character - see
 * `varbit.toplevel_v2_layout_slot`.
 */
class LayoutService(
    private val repository: CentralLayoutRepository,
) {
    private val logger = LoggerFactory.getLogger(LayoutService::class.java)

    sealed class LayoutReply {
        data class Ok(val frame: ByteArray) : LayoutReply()

        data class Fail(val frame: ByteArray) : LayoutReply()
    }

    fun handleSave(payload: LayoutSavePayload): LayoutReply {
        val snapshot = payload.snapshot
        if (payload.characterId <= 0) {
            return fail(WorldOpcodes.LAYOUT_FAIL_UNKNOWN_CHARACTER)
        }
        if (snapshot.slot !in 1..LayoutLimits.MAX_SLOTS) {
            return fail(WorldOpcodes.LAYOUT_FAIL_BAD_SLOT)
        }
        // A layout with nothing in it is not something to store: it would take a slot in the load
        // list and open to an empty screen. Saving one is almost always the frame reporting before
        // it has laid itself out.
        if (snapshot.windows.isEmpty() && snapshot.displays.isEmpty()) {
            return fail(WorldOpcodes.LAYOUT_FAIL_EMPTY)
        }
        return try {
            val saved = repository.saveLayout(payload.characterId, snapshot.toRow(), snapshot.shared)
            if (saved) ok() else fail(WorldOpcodes.LAYOUT_FAIL_UNKNOWN_CHARACTER)
        } catch (e: Exception) {
            logger.warn("Layout save failed for characterId={} slot={}", payload.characterId, snapshot.slot, e)
            fail(WorldOpcodes.LAYOUT_FAIL_STORAGE)
        }
    }

    fun handleLoad(payload: LayoutSlotPayload): LayoutReply {
        if (payload.characterId <= 0) {
            return fail(WorldOpcodes.LAYOUT_FAIL_UNKNOWN_CHARACTER)
        }
        if (payload.slot !in 1..LayoutLimits.MAX_SLOTS) {
            return fail(WorldOpcodes.LAYOUT_FAIL_BAD_SLOT)
        }
        return try {
            val layout =
                repository.findLayout(payload.characterId, payload.slot)
                    ?: return fail(WorldOpcodes.LAYOUT_FAIL_EMPTY)
            LayoutReply.Ok(
                LayoutPackets.encodeData(layout.toSnapshot(repository.findSharing(payload.characterId))),
            )
        } catch (e: Exception) {
            logger.warn("Layout load failed for characterId={} slot={}", payload.characterId, payload.slot, e)
            fail(WorldOpcodes.LAYOUT_FAIL_STORAGE)
        }
    }

    /**
     * Somebody else's layout, by display name.
     *
     * "No such character" and "that player is not sharing" are told apart deliberately: the first is
     * a typo and the second is worth telling the player about, and collapsing them would make a
     * mistyped name look like a refusal.
     *
     * The sharing flag handed back is the *viewer's* own, not the owner's. It is what edit mode's
     * checkbox shows, and answering with somebody else's setting would flip the viewer's own answer
     * the moment they looked at a shared layout.
     */
    fun handleShared(payload: LayoutSharedPayload): LayoutReply {
        // The scratch slot is not shareable. It is whatever the owner happens to have on screen
        // rather than something they chose to publish, and they never agreed to it being seen.
        if (payload.slot !in 1..LayoutLimits.SAVE_SLOTS) {
            return fail(WorldOpcodes.LAYOUT_FAIL_BAD_SLOT)
        }
        return try {
            val ownerId =
                repository.findCharacterIdByDisplayName(payload.ownerName)
                    ?: return fail(WorldOpcodes.LAYOUT_FAIL_UNKNOWN_CHARACTER)
            if (!repository.findSharing(ownerId)) {
                return fail(WorldOpcodes.LAYOUT_FAIL_NOT_SHARED)
            }
            val layout =
                repository.findLayout(ownerId, payload.slot)
                    ?: return fail(WorldOpcodes.LAYOUT_FAIL_EMPTY)
            // Counted only once the layout is known to be going back, and only when it is somebody
            // else's: loading your own is not an import, and a refusal above is not one either.
            if (ownerId != payload.viewerCharacterId) {
                repository.recordImport(ownerId, payload.slot)
            }
            LayoutReply.Ok(
                LayoutPackets.encodeData(
                    layout.toSnapshot(repository.findSharing(payload.viewerCharacterId)),
                ),
            )
        } catch (e: Exception) {
            logger.warn("Shared layout lookup failed for owner={} slot={}", payload.ownerName, payload.slot, e)
            fail(WorldOpcodes.LAYOUT_FAIL_STORAGE)
        }
    }

    fun handleList(characterId: Int): LayoutReply {
        if (characterId <= 0) {
            return fail(WorldOpcodes.LAYOUT_FAIL_UNKNOWN_CHARACTER)
        }
        return try {
            val slots =
                repository.listSlots(characterId, LayoutLimits.SCRATCH_SLOT).map { row ->
                    LayoutSummary(
                        slot = row.slot,
                        nameKey = row.nameKey ?: NO_NAME,
                        screenWidth = row.screenWidth,
                        screenHeight = row.screenHeight,
                    )
                }
            LayoutReply.Ok(
                LayoutPackets.encodeListOk(
                    LayoutListSnapshot(repository.findSharing(characterId), slots),
                ),
            )
        } catch (e: Exception) {
            logger.warn("Layout list failed for characterId={}", characterId, e)
            fail(WorldOpcodes.LAYOUT_FAIL_STORAGE)
        }
    }

    private fun ok(): LayoutReply = LayoutReply.Ok(LayoutPackets.encodeOk())

    private fun fail(reason: Int): LayoutReply = LayoutReply.Fail(LayoutPackets.encodeFail(reason))

    private fun LayoutSnapshot.toRow(): LayoutPresetRow =
        LayoutPresetRow(
            slot = slot,
            nameKey = nameKey.takeIf { it >= 0 },
            screenWidth = screenWidth.coerceAtLeast(0),
            screenHeight = screenHeight.coerceAtLeast(0),
            // Trimmed rather than refused: the wire caps are generous ceilings, and a frame that has
            // grown past one should lose the overflow, not the layout.
            windows =
                windows.take(LayoutLimits.MAX_WINDOWS).map { window ->
                    LayoutWindowRow(
                        panel = window.panel,
                        x = window.x,
                        y = window.y,
                        width = window.width,
                        height = window.height,
                        hostPanel = window.hostPanel.takeIf { it >= 0 },
                        tabOrder = window.tabOrder,
                    )
                },
            displays =
                displays.take(LayoutLimits.MAX_DISPLAYS).map { display ->
                    LayoutDisplayRow(
                        display = display.display,
                        x = display.x,
                        y = display.y,
                        width = display.width,
                        height = display.height,
                    )
                },
        )

    private fun LayoutPresetRow.toSnapshot(shared: Boolean): LayoutSnapshot =
        LayoutSnapshot(
            slot = slot,
            nameKey = nameKey ?: NO_NAME,
            shared = shared,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            windows =
                windows.map { window ->
                    LayoutWindow(
                        panel = window.panel,
                        x = window.x,
                        y = window.y,
                        width = window.width,
                        height = window.height,
                        hostPanel = window.hostPanel ?: NO_HOST,
                        tabOrder = window.tabOrder,
                    )
                },
            displays =
                displays.map { display ->
                    LayoutDisplay(
                        display = display.display,
                        x = display.x,
                        y = display.y,
                        width = display.width,
                        height = display.height,
                    )
                },
        )

    private companion object {
        /** Wire value for a layout the player left unnamed; the column it maps to is NULL. */
        const val NO_NAME: Int = -1

        /** Wire value for a window that is not docked into anything. */
        const val NO_HOST: Int = -1
    }
}
