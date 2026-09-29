package dev.or2.central.notify.handlers

import dev.or2.central.notify.NotifyBroadcaster
import dev.or2.central.notify.NotifyJson.int
import dev.or2.central.notify.NotifyJson.long
import dev.or2.central.notify.NotifyJson.parseObject
import dev.or2.central.notify.NotifyJson.string
import dev.or2.central.notify.PgNotifyChannel
import dev.or2.central.notify.PgNotifyHandler
import dev.or2.central.worldlink.protocol.packets.outgoing.impl.ServerGamemodeUpdatePacket
import org.slf4j.LoggerFactory

@PgNotifyChannel("character_game_mode_events")
class CharacterGameModeNotifyHandler(
    private val broadcaster: NotifyBroadcaster,
) : PgNotifyHandler {
    private val log = LoggerFactory.getLogger(CharacterGameModeNotifyHandler::class.java)

    override fun handle(payload: String?) {
        val root = parseObject(payload) ?: return
        val accountId = root.long("account_id") ?: return
        val characterId = root.int("character_id") ?: return
        val gameMode = root.string("game_mode")
        runCatching {
            val worlds = broadcaster.worldsForAccount(accountId)
            broadcaster.push(
                worlds,
                ServerGamemodeUpdatePacket.encode(ServerGamemodeUpdatePacket.Payload(accountId, characterId, gameMode)),
                broadcastAll = true,
            )
        }.onFailure {
            log.warn("Game mode push failed for {}", accountId, it)
        }
    }
}
