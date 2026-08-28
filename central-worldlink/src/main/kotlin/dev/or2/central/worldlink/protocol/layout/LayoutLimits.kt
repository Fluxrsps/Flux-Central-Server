package dev.or2.central.worldlink.protocol.layout

/**
 * Layout protocol limits (not wire layout — sizes come from
 * [dev.or2.central.worldlink.protocol.FieldKind]).
 *
 * These are ceilings the wire is willing to carry, not the numbers the frame actually uses. The
 * frame's own limits live in the cache — how many panels `dbtable.toplevel_v2_panel` has, how many
 * slots `~toplevel_v2_custom_slots` offers — and Central has no view of it, so they are set well
 * clear of any plausible growth rather than kept in step with something it cannot read.
 */
object LayoutLimits {
    /** Slots a player can save into, matching `~toplevel_v2_custom_slots`. */
    const val SAVE_SLOTS: Int = 5

    /**
     * The scratch slot, which the frame keeps in step with whatever is on screen.
     *
     * Not one of [SAVE_SLOTS] and never offered in a list: it is not a layout the player made, it is
     * the layout they are *in*. Every move, resize, dock and close is written here shortly after it
     * happens, so logging out - or crashing - and coming back puts the windows where they were left
     * without the named slot they had loaded being touched.
     */
    const val SCRATCH_SLOT: Int = 6

    /** Slots per character on the wire and in the table's CHECK: the save slots plus the scratch. */
    const val MAX_SLOTS: Int = SCRATCH_SLOT

    /** Windows in one layout. One per panel the frame has, with room to spare. */
    const val MAX_WINDOWS: Int = 64

    /** Display windows in one layout — the bank, the central interface, split private chat. */
    const val MAX_DISPLAYS: Int = 32

    const val OWNER_NAME_MAX_UTF8: Int = 96

    /** `int panel, int x, int y, int width, int height, int host_panel, int tab_order`. */
    const val WINDOW_ENTRY_BYTES: Int = 7 * 4

    /** `int display, int x, int y, int width, int height`. */
    const val DISPLAY_ENTRY_BYTES: Int = 5 * 4

    /** `byte slot, int name_key, int screen_width, int screen_height`. */
    const val SUMMARY_ENTRY_BYTES: Int = 1 + 4 + 4 + 4
}
