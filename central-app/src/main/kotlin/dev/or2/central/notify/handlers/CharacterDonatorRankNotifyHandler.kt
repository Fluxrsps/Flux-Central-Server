package dev.or2.central.notify.handlers

import dev.or2.central.notify.NotifyBroadcaster
import dev.or2.central.notify.NotifyJson.int
import dev.or2.central.notify.NotifyJson.long
import dev.or2.central.notify.NotifyJson.parseObject
import dev.or2.central.notify.NotifyJson.string
import dev.or2.central.notify.PgNotifyChannel
import dev.or2.central.notify.PgNotifyHandler
import dev.or2.central.worldlink.protocol.packets.outgoing.impl.ServerDonatorUpdatePacket
import org.slf4j.LoggerFactory

@PgNotifyChannel("character_donator_rank_events")
class CharacterDonatorRankNotifyHandler(
    private val broadcaster: NotifyBroadcaster,
) : PgNotifyHandler {
    private val log = LoggerFactory.getLogger(CharacterDonatorRankNotifyHandler::class.java)

    override fun handle(payload: String?) {
        val root = parseObject(payload) ?: return
        val accountId = root.long("account_id") ?: return
        val characterId = root.int("character_id") ?: return
        val donatorRank = root.string("donator_rank")
        runCatching {
            val worlds = broadcaster.worldsForAccount(accountId)
            broadcaster.push(
                worlds,
                ServerDonatorUpdatePacket.encode(ServerDonatorUpdatePacket.Payload(accountId, characterId, donatorRank)),
                broadcastAll = true,
            )
        }.onFailure {
            log.warn("Donator rank push failed for {}", accountId, it)
        }
    }
}
