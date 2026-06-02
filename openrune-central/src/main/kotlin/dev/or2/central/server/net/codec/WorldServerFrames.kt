/**
 * Application-layer world ↔ central codec (per-opcode read/write helpers).
 *
 * **Transport:** each payload is wrapped in a 4-byte big-endian length prefix on the wire;
 * see [dev.or2.central.server.net.WorldServerChannelInitializer] for the Netty pipeline.
 */
package dev.or2.central.server.net.codec

import dev.or2.central.server.net.protocol.WorldServerOpcodes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Reads the opcode byte and exposes the rest of one [WorldServerInboundPacket] as a [FrameInput] stream.
 */
fun readInboundPacketPayload(payload: ByteArray): FrameInput {
    val din = DataInputStream(ByteArrayInputStream(payload))
    val opcode = din.readUnsignedByte()
    return FrameInput(opcode, din, payload.size - 1)
}

data class FrameInput(
    val opcode: Int,
    private val din: DataInputStream,
    val remainingAfterOpcode: Int,
) {
    fun trailingUnreadBytes(): Int = din.available()

    fun readMagic(): Int = din.readInt()

    fun readUnsignedShortCompat(): Int = din.readUnsignedShort()

    fun readIntCompat(): Int = din.readInt()

    fun readLongCompat(): Long = din.readLong()

    fun readFully(len: Int): ByteArray {
        val out = ByteArray(len)
        din.readFully(out)
        return out
    }

    fun readUtf8LenPrefixed(): String {
        val len = din.readUnsignedShort()
        val bytes = readFully(len)
        return String(bytes, StandardCharsets.UTF_8)
    }
}

fun writeHelloAck(): ByteArray {
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeByte(WorldServerOpcodes.OP_HELLO_ACK)
    d.flush()
    return out.toByteArray()
}

fun writePushSubscribeAck(): ByteArray {
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeByte(WorldServerOpcodes.OP_PUSH_SUBSCRIBE_ACK)
    d.flush()
    return out.toByteArray()
}

fun writeHelloReject(reason: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeByte(WorldServerOpcodes.OP_HELLO_REJECT)
    d.writeByte(reason)
    d.flush()
    return out.toByteArray()
}

/**
 * Writes `OP_LOGIN_FAIL`: 1-byte opcode, 4-byte failure code, then optionally three length-prefixed UTF-8 lines.
 * Script lines are only appended when [clientProtocolVersion] is 5 or higher; v2–v4 peers must treat the
 * frame as ending after the int code. Account-name policy responses may use wire code 12 (`LOGIN_FAIL_WORLD_ACCESS`)
 * with a script trailer for legacy clients; see `WorldServerOpcodes.LOGIN_FAIL_ACCOUNT_NAME`.
 */
fun writeLoginFail(
    code: Int,
    clientProtocolVersion: Int = 0,
    scriptLines: Triple<String, String, String>? = null,
): ByteArray {
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeByte(WorldServerOpcodes.OP_LOGIN_FAIL)
    d.writeInt(code)
    if (clientProtocolVersion >= 5 && scriptLines != null) {
        fun writeLine(line: String) {
            val utf8 =
                utf8TruncatedTo(
                    line,
                    WorldServerOpcodes.LOGIN_FAIL_SCRIPT_LINE_MAX_UTF8_BYTES,
                )
            require(utf8.size <= 65535) { "login fail script line length" }
            d.writeShort(utf8.size)
            d.write(utf8)
        }
        writeLine(scriptLines.first)
        writeLine(scriptLines.second)
        writeLine(scriptLines.third)
    }
    d.flush()
    val body = out.toByteArray()
    val bodyLenAfterOpcode = body.size - 1
    require(bodyLenAfterOpcode <= WorldServerInboundFrameSpecs.loginFailMaxBody()) {
        "LOGIN_FAIL body length"
    }
    return body
}

fun writeLoginOk(
    token: ByteArray,
    accountId: Long,
    rights: String,
    clientProtocolVersion: Int,
): ByteArray {
    require(token.size == WorldServerOpcodes.TOKEN_BYTES) { "token size" }
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeByte(WorldServerOpcodes.OP_LOGIN_OK)
    d.writeShort(token.size)
    d.write(token)
    d.writeLong(accountId)
    if (clientProtocolVersion >= 3) {
        val rightsUtf8 = utf8TruncatedTo(rights.ifBlank { "" }, WorldServerOpcodes.LOGIN_OK_RIGHTS_MAX_BYTES)
        require(rightsUtf8.size <= 65535) { "rights length" }
        d.writeShort(rightsUtf8.size)
        d.write(rightsUtf8)
    }
    d.flush()
    val body = out.toByteArray()
    val bodyLenAfterOpcode = body.size - 1
    require(bodyLenAfterOpcode <= WorldServerInboundFrameSpecs.loginOkMaxBody(clientProtocolVersion)) {
        "LOGIN_OK body length"
    }
    return body
}

fun writeLogoutAck(): ByteArray {
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeByte(WorldServerOpcodes.OP_LOGOUT_ACK)
    d.flush()
    return out.toByteArray()
}

fun writeHeartbeatAck(): ByteArray {
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeByte(WorldServerOpcodes.OP_HEARTBEAT_ACK)
    d.flush()
    return out.toByteArray()
}

fun writeServerRevokeLogin(
    accountId: Long,
    characterId: Int,
): ByteArray {
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeByte(WorldServerOpcodes.OP_SERVER_REVOKE_LOGIN)
    d.writeLong(accountId)
    d.writeInt(characterId)
    d.flush()
    return out.toByteArray()
}

fun writeServerMuteUpdate(
    accountId: Long,
    characterId: Int,
    mutedUntilEpochMillis: Long,
): ByteArray {
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeByte(WorldServerOpcodes.OP_SERVER_MUTE_UPDATE)
    d.writeLong(accountId)
    d.writeInt(characterId)
    d.writeLong(mutedUntilEpochMillis)
    d.flush()
    return out.toByteArray()
}

fun writeServerKick(
    accountId: Long,
    characterId: Int,
): ByteArray {
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeByte(WorldServerOpcodes.OP_SERVER_KICK)
    d.writeLong(accountId)
    d.writeInt(characterId)
    d.flush()
    return out.toByteArray()
}

private const val WORLD_OPS_UTF8_MAX: Int = 2048

/** Must match game [org.rsmod.api.net.central.WorldLinkFrameSpecs.PM_RELAY_SENDER_DISPLAY_MAX_UTF8]. */
private const val DISPLAY_NAME_SYNC_CHUNK_MAX_UTF8: Int = 96

fun writeServerRebootSchedule(
    clear: Boolean,
    worldScope: Int,
    rebootAtMs: Long,
    message: String,
): ByteArray {
    val text =
        if (clear) {
            ""
        } else {
            message.ifBlank { "Server reboot scheduled." }
        }
    val utf8 = text.toByteArray(StandardCharsets.UTF_8)
    require(utf8.size <= WORLD_OPS_UTF8_MAX) { "reboot message utf8 length" }
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeByte(WorldServerOpcodes.OP_SERVER_REBOOT)
    d.writeByte(if (clear) 1 else 0)
    d.writeInt(worldScope)
    d.writeLong(if (clear) 0L else rebootAtMs)
    d.writeShort(utf8.size)
    d.write(utf8)
    d.flush()
    return out.toByteArray()
}

fun writeServerDisplayNameSync(
    accountId: Long,
    characterId: Int,
    newDisplayName: String,
    priorDisplayName: String,
): ByteArray {
    val newUtf8 = utf8TruncatedTo(newDisplayName, DISPLAY_NAME_SYNC_CHUNK_MAX_UTF8)
    val priorUtf8 = utf8TruncatedTo(priorDisplayName, DISPLAY_NAME_SYNC_CHUNK_MAX_UTF8)
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeByte(WorldServerOpcodes.OP_SERVER_DISPLAY_NAME_SYNC)
    d.writeLong(accountId)
    d.writeInt(characterId)
    d.writeShort(newUtf8.size)
    d.write(newUtf8)
    d.writeShort(priorUtf8.size)
    d.write(priorUtf8)
    d.flush()
    return out.toByteArray()
}

fun writeServerBroadcast(
    worldScope: Int,
    message: String,
    url: String,
    icon: String,
): ByteArray {
    val m = message.toByteArray(StandardCharsets.UTF_8)
    val u = url.toByteArray(StandardCharsets.UTF_8)
    val ic = icon.toByteArray(StandardCharsets.UTF_8)
    require(m.size <= WORLD_OPS_UTF8_MAX) { "broadcast message utf8 length" }
    require(u.size <= WORLD_OPS_UTF8_MAX) { "broadcast url utf8 length" }
    require(ic.size <= WORLD_OPS_UTF8_MAX) { "broadcast icon utf8 length" }
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeByte(WorldServerOpcodes.OP_SERVER_BROADCAST)
    d.writeInt(worldScope)
    d.writeShort(m.size)
    d.write(m)
    d.writeShort(u.size)
    d.write(u)
    d.writeShort(ic.size)
    d.write(ic)
    d.flush()
    return out.toByteArray()
}

private fun utf8TruncatedTo(
    s: String,
    maxBytes: Int,
): ByteArray {
    val full = s.toByteArray(StandardCharsets.UTF_8)
    if (full.size <= maxBytes) return full
    var cut = maxBytes
    while (cut > 0 && (full[cut - 1].toInt() and 0xC0) == 0x80) {
        cut--
    }
    return full.copyOf(cut)
}
