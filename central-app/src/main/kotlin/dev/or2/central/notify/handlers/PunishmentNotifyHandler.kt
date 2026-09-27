package dev.or2.central.notify.handlers

import dev.or2.central.exchange.ExchangeEngine
import dev.or2.central.exchange.model.CancelReason
import dev.or2.central.notify.NotifyBroadcaster
import dev.or2.central.notify.NotifyJson
import dev.or2.central.notify.NotifyJson.int
import dev.or2.central.notify.NotifyJson.long
import dev.or2.central.notify.NotifyJson.parseObject
import dev.or2.central.notify.NotifyJson.string
import dev.or2.central.notify.PgNotifyChannel
import dev.or2.central.notify.PgNotifyHandler
import dev.or2.central.worldlink.protocol.packets.outgoing.impl.ServerRevokeLoginPacket
import org.slf4j.LoggerFactory

@PgNotifyChannel("punishment_events")
class PunishmentNotifyHandler(
    private val broadcaster: NotifyBroadcaster,
    private val exchange: ExchangeEngine? = null,
) : PgNotifyHandler {
    private val log = LoggerFactory.getLogger(PunishmentNotifyHandler::class.java)

    override fun handle(payload: String?) {
        val root = parseObject(payload) ?: return
        val accountId = root.long("account_id") ?: return
        val characterId = root.int("character_id") ?: 0
        runCatching {
            val worlds = broadcaster.worldsForAccount(accountId)
            broadcaster.revokeSessionsForAccount(accountId)
            broadcaster.push(
                worlds,
                ServerRevokeLoginPacket.encode(ServerRevokeLoginPacket.Payload(accountId, characterId)),
                broadcastAll = true,
            )
        }.onFailure {
            log.warn("Failed punishment revoke for {}", accountId, it)
        }
        // A ban parks the character's Trading Post assets in their collection box; the box itself
        // stays locked by the ban check on claims until the punishment is lifted.
        runCatching {
            val engine = exchange ?: return
            val cancelled =
                if (root.string("scope") == "character" && characterId > 0) engine.cancelAllLive(characterId, CancelReason.BANNED)
                else engine.cancelAllForAccount(accountId, CancelReason.BANNED)
            if (cancelled > 0) log.info("cancelled {} Trading Post orders for banned account {}", cancelled, accountId)
        }.onFailure {
            log.warn("Failed Trading Post cancellation for banned account {}", accountId, it)
        }
    }
}
