package dev.or2.central.worldlink.protocol

import io.netty.buffer.Unpooled
import java.nio.charset.StandardCharsets

/** Decodes Central → game server-push frames (opcode at index 0). */
object CentralPushPackets {
    data class RevokeLogin(val accountId: Long, val characterId: Int)

    data class Kick(val accountId: Long, val characterId: Int)

    data class MuteUpdate(val accountId: Long, val characterId: Int, val mutedUntilEpochMillis: Long)

    data class Reboot(val clear: Boolean, val worldScope: Int, val rebootAtMs: Long, val message: String)

    data class Broadcast(val worldScope: Int, val message: String, val url: String, val icon: String)

    data class DisplayNameSync(
        val accountId: Long,
        val characterId: Int,
        val newDisplayName: String,
        val priorDisplayName: String,
    )

    data class DiscordIdSync(
        val accountId: Long,
        val discordId: String,
    )

    data class RightsUpdate(val accountId: Long, val rights: String)

    data class DonatorUpdate(val accountId: Long, val characterId: Int, val donatorRank: String)

    data class GamemodeUpdate(val accountId: Long, val characterId: Int, val gameMode: String)

    data class TeleportHome(val accountId: Long, val characterId: Int)

    data class ExchangeNotify(
        val characterId: Int,
        val notificationId: Long,
        val kind: String,
        val orderId: Long,
        val objId: Int,
        val payloadJson: String,
    )

    fun decodeExchangeNotify(frame: ByteArray): ExchangeNotify =
        readBody(frame).use { r ->
            ExchangeNotify(
                characterId = r.readInt(),
                notificationId = r.readLong(),
                kind = r.readUtf8LenPrefixed(),
                orderId = r.readLong(),
                objId = r.readInt(),
                payloadJson = r.readUtf8LenPrefixed(),
            )
        }

    fun decodeRevokeLogin(frame: ByteArray): RevokeLogin =
        readBody(frame).use { r ->
            RevokeLogin(r.readLong(), r.readInt())
        }

    fun decodeKick(frame: ByteArray): Kick =
        readBody(frame).use { r ->
            Kick(r.readLong(), r.readInt())
        }

    fun decodeMuteUpdate(frame: ByteArray): MuteUpdate =
        readBody(frame).use { r ->
            MuteUpdate(r.readLong(), r.readInt(), r.readLong())
        }

    fun decodeRightsUpdate(frame: ByteArray): RightsUpdate =
        readBody(frame).use { r ->
            RightsUpdate(r.readLong(), r.readUtf8LenPrefixed())
        }

    fun decodeDonatorUpdate(frame: ByteArray): DonatorUpdate =
        readBody(frame).use { r ->
            DonatorUpdate(r.readLong(), r.readInt(), r.readUtf8LenPrefixed())
        }

    fun decodeGamemodeUpdate(frame: ByteArray): GamemodeUpdate =
        readBody(frame).use { r ->
            GamemodeUpdate(r.readLong(), r.readInt(), r.readUtf8LenPrefixed())
        }

    fun decodeTeleportHome(frame: ByteArray): TeleportHome =
        readBody(frame).use { r ->
            TeleportHome(r.readLong(), r.readInt())
        }

    fun decodeReboot(frame: ByteArray): Reboot =
        readBody(frame).use { r ->
            val kind = r.readUnsignedByte()
            val worldScope = r.readInt()
            val rebootAtMs = r.readLong()
            val message = r.readUtf8LenPrefixed()
            Reboot(clear = kind == 1, worldScope, rebootAtMs, message)
        }

    fun decodeBroadcast(frame: ByteArray): Broadcast =
        readBody(frame).use { r ->
            Broadcast(
                worldScope = r.readInt(),
                message = r.readUtf8LenPrefixed(),
                url = r.readUtf8LenPrefixed(),
                icon = r.readUtf8LenPrefixed(),
            )
        }

    fun decodeDisplayNameSync(frame: ByteArray): DisplayNameSync =
        readBody(frame).use { r ->
            DisplayNameSync(
                accountId = r.readLong(),
                characterId = r.readInt(),
                newDisplayName = r.readUtf8LenPrefixed(),
                priorDisplayName = r.readUtf8LenPrefixed(),
            )
        }

    fun decodeDiscordIdSync(frame: ByteArray): DiscordIdSync =
        readBody(frame).use { r ->
            DiscordIdSync(
                accountId = r.readLong(),
                discordId = r.readUtf8LenPrefixed(),
            )
        }

    fun decodePrivateMessage(frame: ByteArray): dev.or2.central.worldlink.protocol.social.PrivateMessagePush =
        readBody(frame).use { r -> dev.or2.central.worldlink.protocol.social.SocialPackets.decodeServerPrivateMessage(r) }

    fun decodeFriendPresence(frame: ByteArray): dev.or2.central.worldlink.protocol.social.FriendPresencePush =
        readBody(frame).use { r -> dev.or2.central.worldlink.protocol.social.SocialPackets.decodeServerFriendPresence(r) }

    private fun readBody(frame: ByteArray): FrameReader {
        val buf = Unpooled.wrappedBuffer(frame)
        val opcode = buf.readUnsignedByte().toInt()
        return FrameReader(opcode, buf, buf.readableBytes())
    }
}
