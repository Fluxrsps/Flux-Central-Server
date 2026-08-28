package dev.or2.central.layout

import dev.or2.sql.OpenRuneSql
import java.sql.Connection
import java.sql.Types
import javax.sql.DataSource

/**
 * A window in a stored layout. Coordinates are passed through untouched — see
 * `V27__layout_presets.sql` for what they mean and why Central does not interpret them.
 */
data class LayoutWindowRow(
    val panel: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val hostPanel: Int?,
    val tabOrder: Int,
)

data class LayoutDisplayRow(
    val display: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class LayoutPresetRow(
    val slot: Int,
    val nameKey: Int?,
    val screenWidth: Int,
    val screenHeight: Int,
    val windows: List<LayoutWindowRow>,
    val displays: List<LayoutDisplayRow>,
)

/**
 * One slot, without what is in it — enough to label a row in the layout list.
 *
 * No sharing flag: that is one answer for the character, not one per slot. See [findSharing].
 */
data class LayoutSummaryRow(
    val slot: Int,
    val nameKey: Int?,
    val screenWidth: Int,
    val screenHeight: Int,
)

/**
 * Storage for player-authored `toplevel_v2` layouts.
 *
 * Deliberately separate from everything the frame ships with: those live in the cache, are the same
 * for everyone and change when the cache is rebuilt. See `V27__layout_presets.sql`.
 *
 * A layout is three tables — the slot, its windows and its display windows — and is only ever
 * written whole. [saveLayout] replaces the contents of a slot in one transaction rather than
 * reconciling it, because a half-applied layout is not a layout: a window left behind from the
 * previous save would open somewhere the player never put it.
 */
class CentralLayoutRepository(
    private val dataSource: DataSource,
) {
    /**
     * The player's own saved layouts.
     *
     * [hiddenSlot] is dropped from the result - the scratch slot, which is a record of what is on
     * screen rather than a layout anyone chose to keep, and must never turn up in a list they pick
     * from. Passed in rather than assumed here, because which slot that is belongs to the protocol.
     */
    fun listSlots(
        characterId: Int,
        hiddenSlot: Int,
    ): List<LayoutSummaryRow> {
        if (characterId <= 0) {
            return emptyList()
        }
        return dataSource.connection.use { conn ->
            conn.prepare("list_slots.sql").use { ps ->
                ps.setInt(1, characterId)
                ps.setInt(2, hiddenSlot)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                LayoutSummaryRow(
                                    slot = rs.getInt("slot"),
                                    nameKey = rs.getInt("name_key").takeUnless { rs.wasNull() },
                                    screenWidth = rs.getInt("screen_width"),
                                    screenHeight = rs.getInt("screen_height"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun findLayout(
        characterId: Int,
        slot: Int,
    ): LayoutPresetRow? {
        if (characterId <= 0) {
            return null
        }
        return dataSource.connection.use { conn ->
            val header = conn.selectHeader(characterId, slot) ?: return@use null
            val (presetId, summary) = header
            LayoutPresetRow(
                slot = summary.slot,
                nameKey = summary.nameKey,
                screenWidth = summary.screenWidth,
                screenHeight = summary.screenHeight,
                windows = conn.selectWindows(presetId),
                displays = conn.selectDisplays(presetId),
            )
        }
    }

    /**
     * Whether [characterId] lets other people load their layouts.
     *
     * A character with no row has never said either way, and the answer for them is yes - the same
     * as the column's default. Sharing is the normal state; the setting exists for the people who
     * want out of it.
     */
    fun findSharing(characterId: Int): Boolean {
        if (characterId <= 0) {
            return SHARED_BY_DEFAULT
        }
        return dataSource.connection.use { conn ->
            conn.prepare("select_sharing.sql").use { ps ->
                ps.setInt(1, characterId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getBoolean("shared") else SHARED_BY_DEFAULT
                }
            }
        }
    }

    fun setSharing(
        characterId: Int,
        shared: Boolean,
    ) {
        if (characterId <= 0) {
            return
        }
        dataSource.connection.use { conn ->
            conn.prepare("upsert_sharing.sql").use { ps ->
                ps.setInt(1, characterId)
                ps.setBoolean(2, shared)
                ps.executeUpdate()
            }
        }
    }

    /**
     * Counts one import of [ownerCharacterId]'s layout in [slot].
     *
     * Fire-and-forget by design: a board is not worth failing a fetch over, so the caller records
     * the import after the layout has already been handed back. See `LayoutService.handleShared`.
     */
    fun recordImport(
        ownerCharacterId: Int,
        slot: Int,
    ) {
        if (ownerCharacterId <= 0) {
            return
        }
        dataSource.connection.use { conn ->
            conn.prepare("increment_import_count.sql").use { ps ->
                ps.setInt(1, ownerCharacterId)
                ps.setInt(2, slot)
                ps.executeUpdate()
            }
        }
    }

    fun findCharacterIdByDisplayName(name: String): Int? {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) {
            return null
        }
        return dataSource.connection.use { conn ->
            conn.prepare("find_character_by_display_name.sql").use { ps ->
                ps.setString(1, cleaned)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("id") else null
                }
            }
        }
    }

    /**
     * Replaces whatever is in [slot], and records the character's sharing preference.
     *
     * Sharing is written here rather than through its own call because it arrives with the save:
     * edit mode asks the question on the same panel, and the two travelling together is what makes
     * pressing the button do exactly what the panel said it would.
     */
    fun saveLayout(
        characterId: Int,
        layout: LayoutPresetRow,
        shared: Boolean,
    ): Boolean {
        if (characterId <= 0) {
            return false
        }
        return dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val presetId = conn.upsertHeader(characterId, layout)
                conn.deleteChildren(presetId)
                conn.insertWindows(presetId, layout.windows)
                conn.insertDisplays(presetId, layout.displays)
                conn.prepare("upsert_sharing.sql").use { ps ->
                    ps.setInt(1, characterId)
                    ps.setBoolean(2, shared)
                    ps.executeUpdate()
                }
                conn.commit()
                true
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    private fun Connection.selectHeader(
        characterId: Int,
        slot: Int,
    ): Pair<Int, LayoutSummaryRow>? =
        prepare("select_preset.sql").use { ps ->
            ps.setInt(1, characterId)
            ps.setInt(2, slot)
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    return@use null
                }
                rs.getInt("id") to
                    LayoutSummaryRow(
                        slot = rs.getInt("slot"),
                        nameKey = rs.getInt("name_key").takeUnless { rs.wasNull() },
                        screenWidth = rs.getInt("screen_width"),
                        screenHeight = rs.getInt("screen_height"),
                    )
            }
        }

    private fun Connection.selectWindows(presetId: Int): List<LayoutWindowRow> =
        prepare("select_windows.sql").use { ps ->
            ps.setInt(1, presetId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            LayoutWindowRow(
                                panel = rs.getInt("panel"),
                                x = rs.getInt("x"),
                                y = rs.getInt("y"),
                                width = rs.getInt("width"),
                                height = rs.getInt("height"),
                                hostPanel = rs.getInt("host_panel").takeUnless { rs.wasNull() },
                                tabOrder = rs.getInt("tab_order"),
                            ),
                        )
                    }
                }
            }
        }

    private fun Connection.selectDisplays(presetId: Int): List<LayoutDisplayRow> =
        prepare("select_displays.sql").use { ps ->
            ps.setInt(1, presetId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            LayoutDisplayRow(
                                display = rs.getInt("display"),
                                x = rs.getInt("x"),
                                y = rs.getInt("y"),
                                width = rs.getInt("width"),
                                height = rs.getInt("height"),
                            ),
                        )
                    }
                }
            }
        }

    private fun Connection.upsertHeader(
        characterId: Int,
        layout: LayoutPresetRow,
    ): Int =
        prepare("upsert_preset.sql").use { ps ->
            ps.setInt(1, characterId)
            ps.setInt(2, layout.slot)
            if (layout.nameKey == null) {
                ps.setNull(3, Types.INTEGER)
            } else {
                ps.setInt(3, layout.nameKey)
            }
            ps.setInt(4, layout.screenWidth)
            ps.setInt(5, layout.screenHeight)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "layout upsert returned no id" }
                rs.getInt("id")
            }
        }

    private fun Connection.deleteChildren(presetId: Int) {
        for (statement in listOf("delete_windows.sql", "delete_displays.sql")) {
            prepare(statement).use { ps ->
                ps.setInt(1, presetId)
                ps.executeUpdate()
            }
        }
    }

    private fun Connection.insertWindows(
        presetId: Int,
        windows: List<LayoutWindowRow>,
    ) {
        if (windows.isEmpty()) {
            return
        }
        prepare("insert_window.sql").use { ps ->
            for (window in windows) {
                ps.setInt(1, presetId)
                ps.setInt(2, window.panel)
                ps.setInt(3, window.x)
                ps.setInt(4, window.y)
                ps.setInt(5, window.width)
                ps.setInt(6, window.height)
                if (window.hostPanel == null) {
                    ps.setNull(7, Types.INTEGER)
                } else {
                    ps.setInt(7, window.hostPanel)
                }
                ps.setInt(8, window.tabOrder)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun Connection.insertDisplays(
        presetId: Int,
        displays: List<LayoutDisplayRow>,
    ) {
        if (displays.isEmpty()) {
            return
        }
        prepare("insert_display.sql").use { ps ->
            for (display in displays) {
                ps.setInt(1, presetId)
                ps.setInt(2, display.display)
                ps.setInt(3, display.x)
                ps.setInt(4, display.y)
                ps.setInt(5, display.width)
                ps.setInt(6, display.height)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun Connection.prepare(statement: String) =
        prepareStatement(OpenRuneSql.text("central/layout/$statement"))

    private companion object {
        /** Matches the column default on `character_layout_settings.shared`. */
        const val SHARED_BY_DEFAULT: Boolean = true
    }
}
