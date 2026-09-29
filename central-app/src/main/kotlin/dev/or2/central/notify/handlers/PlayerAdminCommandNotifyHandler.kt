package dev.or2.central.notify.handlers

import dev.or2.central.notify.NotifyBroadcaster
import dev.or2.central.notify.NotifyJson.int
import dev.or2.central.notify.NotifyJson.long
import dev.or2.central.notify.NotifyJson.parseObject
import dev.or2.central.notify.NotifyJson.string
import dev.or2.central.notify.PgNotifyChannel
import dev.or2.central.notify.PgNotifyHandler
import dev.or2.central.worldlink.protocol.packets.outgoing.impl.ServerTeleportHomePacket
import org.slf4j.LoggerFactory

@PgNotifyChannel("player_admin_command_events")
class PlayerAdminCommandNotifyHandler(
    private val broadcaster: NotifyBroadcaster,
) : PgNotifyHandler {
    private val log = LoggerFactory.getLogger(PlayerAdminCommandNotifyHandler::class.java)

    override fun handle(payload: String?) {
        val root = parseObject(payload) ?: return
        val accountId = root.long("account_id") ?: return
        val characterId = root.int("character_id") ?: return
        val kind = root.string("kind")

        // Unknown kinds are a no-op, not an error — lets future kinds be added to the DB check
        // constraint before this handler knows how to push them.
        if (kind != "teleport_home") {
            return
        }

        runCatching {
            val worlds = broadcaster.worldsForAccount(accountId)
            broadcaster.push(
                worlds,
                ServerTeleportHomePacket.encode(ServerTeleportHomePacket.Payload(accountId, characterId)),
                broadcastAll = true,
            )
        }.onFailure {
            log.warn("Teleport home push failed for {}", accountId, it)
        }
    }
}
