package dev.or2.central.worldlink.protocol.layout

/**
 * One window in a saved `toplevel_v2` layout.
 *
 * [panel] is the panel's `dbrow` id, not the window slot it happened to be in. Which slot renders a
 * panel is decided fresh on every login and means nothing between sessions; the panel does not.
 *
 * [x], [y], [width] and [height] are 0..4095 fractions of the client window rather than pixels —
 * see `~toplevel_v2_window_normalise`. That is what lets a layout saved on one screen open correctly
 * on another, and it is why Central stores them without interpreting them.
 */
data class LayoutWindow(
    val panel: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** The panel this one is docked into as a tab, or -1 when it is a window in its own right. */
    val hostPanel: Int,
    /** Place in the host's tab strip. 0 for an undocked window, or for the one heading a strip. */
    val tabOrder: Int,
)

/**
 * One display window — the bank, the central interface, split private chat.
 *
 * [display] is the `dbrow` id from `dbtable.toplevel_v2_display`. Unlike [LayoutWindow] these are
 * absolute pixels: a display window's rect lives on a real component rather than in the frame's own
 * normalised store, so there is nothing to resolve and nothing that would resolve it.
 */
data class LayoutDisplay(
    val display: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * A whole layout, as it goes over the wire in either direction.
 *
 * [nameKey] is a key into `enum.toplevel_v2_layout_name`, or -1 for a layout the player left
 * unnamed. -1 rather than null on the wire, because the frame already treats an absent name as a
 * real answer and the column it lands in is the one that is nullable.
 *
 * [shared] is a fact about the *character*, not about this slot — one answer for all five, stored in
 * `character_layout_settings`. It travels with a layout because that is when the frame asks the
 * question: edit mode's checkbox sits on the panel that saves.
 */
data class LayoutSnapshot(
    val slot: Int,
    val nameKey: Int,
    val shared: Boolean,
    val screenWidth: Int,
    val screenHeight: Int,
    val windows: List<LayoutWindow>,
    val displays: List<LayoutDisplay>,
)

/** One slot of the layout list: enough to label a row without sending what is in it. */
data class LayoutSummary(
    val slot: Int,
    val nameKey: Int,
    val screenWidth: Int,
    val screenHeight: Int,
)

/**
 * Reply to [dev.or2.central.worldlink.protocol.WorldOpcodes.OP_WORLD_LAYOUT_LIST]: what the player
 * has, and whether they are letting anyone else use it.
 *
 * Which layout they are currently in is not here. That is a varbit on the character - nobody but the
 * world they are logged into ever reads it - see `varbit.toplevel_v2_layout_slot`.
 */
data class LayoutListSnapshot(
    val shared: Boolean,
    val slots: List<LayoutSummary>,
)

data class LayoutSavePayload(
    val characterId: Int,
    val snapshot: LayoutSnapshot,
)

data class LayoutSlotPayload(
    val characterId: Int,
    val slot: Int,
)

data class LayoutCharacterPayload(
    val characterId: Int,
)

/** A request for somebody else's layout. [ownerName] is a display name, as typed. */
data class LayoutSharedPayload(
    val viewerCharacterId: Int,
    val ownerName: String,
    val slot: Int,
)
