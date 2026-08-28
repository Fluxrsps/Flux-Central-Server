package dev.or2.central.worldlink.protocol

enum class PacketDirection {
    INBOUND,
    OUTBOUND,
}

enum class FieldKind(
    val maxBytes: Int = 0,
    val maxEntries: Int = 0,
    val optional: Boolean = false,
) {
    BYTE,
    USHORT,
    INT,
    LONG,

    INT_OPTIONAL(optional = true),

    STRING_96(maxBytes = 96),
    STRING_128(maxBytes = 128),
    STRING_2048(maxBytes = 2048),
    STRING_LOGIN_USERNAME(maxBytes = PacketLimits.LOGIN_USERNAME_MAX_UTF8),
    STRING_LOGIN_PASSWORD(maxBytes = PacketLimits.LOGIN_PASSWORD_MAX_UTF8),
    STRING_LOGIN_RIGHTS(maxBytes = WorldOpcodes.LOGIN_OK_RIGHTS_MAX_BYTES, optional = true),
    STRING_LOGIN_SCRIPT(maxBytes = WorldOpcodes.LOGIN_FAIL_SCRIPT_LINE_MAX_UTF8_BYTES, optional = true),
    STRING_PM(maxBytes = dev.or2.central.worldlink.protocol.social.SocialLimits.PM_MESSAGE_MAX_UTF8),

    BYTES_WORLD_KEY(maxBytes = PacketLimits.WORLD_KEY_MAX_BYTES),
    FIXED_TOKEN(maxBytes = WorldOpcodes.TOKEN_BYTES),

    /** `u16 count` + up to [maxEntries] × (display name + previous name + world id). */
    SOCIAL_FRIEND_LIST(maxEntries = dev.or2.central.worldlink.protocol.social.SocialLimits.MAX_LIST_ENTRIES),

    /** `u16 count` + up to [maxEntries] × (display name + previous name). */
    SOCIAL_IGNORE_LIST(maxEntries = dev.or2.central.worldlink.protocol.social.SocialLimits.MAX_LIST_ENTRIES),

    STRING_LAYOUT_OWNER(maxBytes = dev.or2.central.worldlink.protocol.layout.LayoutLimits.OWNER_NAME_MAX_UTF8),

    /** `u16 count` + up to [maxEntries] × (panel, x, y, width, height, host panel, tab order). */
    LAYOUT_WINDOW_LIST(maxEntries = dev.or2.central.worldlink.protocol.layout.LayoutLimits.MAX_WINDOWS),

    /** `u16 count` + up to [maxEntries] × (display, x, y, width, height). */
    LAYOUT_DISPLAY_LIST(maxEntries = dev.or2.central.worldlink.protocol.layout.LayoutLimits.MAX_DISPLAYS),

    /** `u16 count` + up to [maxEntries] × (slot, name key, shared, screen width, screen height). */
    LAYOUT_SUMMARY_LIST(maxEntries = dev.or2.central.worldlink.protocol.layout.LayoutLimits.MAX_SLOTS),
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WorldPacketIncoming(
    val opcode: Int,
    val name: String,
    val fields: Array<FieldKind> = [],
    /** When non-empty, body length must be exactly one of these values (overrides min/max from [fields]). */
    val allowedBodyBytes: IntArray = [],
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WorldPacketOutgoing(
    val opcode: Int,
    val name: String,
    val fields: Array<FieldKind> = [],
    /** When non-empty, body length must be exactly one of these values (overrides min/max from [fields]). */
    val allowedBodyBytes: IntArray = [],
)

data class PacketDef(
    val opcode: Int,
    val name: String,
    val direction: PacketDirection,
    val minBodyBytes: Int,
    val maxBodyBytes: Int,
    val fields: List<FieldKind> = emptyList(),
    val allowedBodyBytes: IntArray? = null,
    val sourceClass: String = "",
) {
    fun sizeFailure(shortOrLong: String): String = "${name.lowercase()}_$shortOrLong"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketDef) return false
        return opcode == other.opcode &&
            name == other.name &&
            direction == other.direction &&
            minBodyBytes == other.minBodyBytes &&
            maxBodyBytes == other.maxBodyBytes &&
            fields == other.fields &&
            allowedBodyBytes.contentEquals(other.allowedBodyBytes) &&
            sourceClass == other.sourceClass
    }

    override fun hashCode(): Int {
        var result = opcode
        result = 31 * result + name.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + minBodyBytes
        result = 31 * result + maxBodyBytes
        result = 31 * result + fields.hashCode()
        result = 31 * result + (allowedBodyBytes?.contentHashCode() ?: 0)
        result = 31 * result + sourceClass.hashCode()
        return result
    }
}

object PacketBodySize {
    fun fromFields(fields: Array<FieldKind>): Pair<Int, Int> {
        if (fields.isEmpty()) return 0 to 0
        var min = 0
        var max = 0
        for (field in fields) {
            val size = field.wireSize()
            if (!field.optional) min += size.minBytes
            max += size.maxBytes
        }
        return min to max
    }

    private fun FieldKind.wireSize(): WireSize =
        when (this) {
            FieldKind.BYTE -> WireSize(1, 1)
            FieldKind.USHORT -> WireSize(2, 2)
            FieldKind.INT, FieldKind.INT_OPTIONAL -> WireSize(4, 4)
            FieldKind.LONG -> WireSize(8, 8)
            FieldKind.STRING_96,
            FieldKind.STRING_128,
            FieldKind.STRING_2048,
            FieldKind.STRING_LOGIN_USERNAME,
            FieldKind.STRING_LOGIN_PASSWORD,
            FieldKind.STRING_LOGIN_RIGHTS,
            FieldKind.STRING_LOGIN_SCRIPT,
            FieldKind.STRING_PM,
            FieldKind.STRING_LAYOUT_OWNER,
            -> {
                require(maxBytes > 0) { "$this requires maxBytes" }
                WireSize(2, 2 + maxBytes)
            }
            FieldKind.BYTES_WORLD_KEY -> {
                require(maxBytes > 0) { "$this requires maxBytes" }
                WireSize(2, 2 + maxBytes)
            }
            FieldKind.FIXED_TOKEN -> {
                require(maxBytes > 0) { "$this requires maxBytes" }
                val fixed = 2 + maxBytes
                WireSize(fixed, fixed)
            }
            FieldKind.SOCIAL_FRIEND_LIST -> {
                require(maxEntries > 0) { "$this requires maxEntries" }
                val entryMax = lenPrefixedUtf8(96) + lenPrefixedUtf8(96) + 4
                WireSize(2, 2 + maxEntries * entryMax)
            }
            FieldKind.SOCIAL_IGNORE_LIST -> {
                require(maxEntries > 0) { "$this requires maxEntries" }
                val entryMax = lenPrefixedUtf8(96) + lenPrefixedUtf8(96)
                WireSize(2, 2 + maxEntries * entryMax)
            }
            FieldKind.LAYOUT_WINDOW_LIST ->
                countedList(dev.or2.central.worldlink.protocol.layout.LayoutLimits.WINDOW_ENTRY_BYTES)
            FieldKind.LAYOUT_DISPLAY_LIST ->
                countedList(dev.or2.central.worldlink.protocol.layout.LayoutLimits.DISPLAY_ENTRY_BYTES)
            FieldKind.LAYOUT_SUMMARY_LIST ->
                countedList(dev.or2.central.worldlink.protocol.layout.LayoutLimits.SUMMARY_ENTRY_BYTES)
        }

    /** `u16 count` followed by up to [FieldKind.maxEntries] fixed-width entries. */
    private fun FieldKind.countedList(entryBytes: Int): WireSize {
        require(maxEntries > 0) { "$this requires maxEntries" }
        return WireSize(2, 2 + maxEntries * entryBytes)
    }

    private fun lenPrefixedUtf8(maxUtf8: Int): Int = 2 + maxUtf8

    private data class WireSize(val minBytes: Int, val maxBytes: Int)
}

interface InboundPacket<P> {
    fun decode(input: FrameReader): P
}

interface OutboundPacket<P> {
    fun encode(payload: P): ByteArray
}

class PacketDecodeException(message: String) : IllegalArgumentException(message)

object PacketLimits {
    const val MAX_INBOUND_FRAMED_BODY: Int = 16 * 1024
    const val WORLD_KEY_MAX_BYTES: Int = 4096
    const val LOGIN_USERNAME_MAX_UTF8: Int = 64 * 4
    const val LOGIN_PASSWORD_MAX_UTF8: Int = 256 * 4
}
