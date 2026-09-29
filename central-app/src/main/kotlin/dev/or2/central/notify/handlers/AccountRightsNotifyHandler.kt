package dev.or2.central.notify.handlers

import dev.or2.central.notify.NotifyBroadcaster
import dev.or2.central.notify.NotifyJson.long
import dev.or2.central.notify.NotifyJson.parseObject
import dev.or2.central.notify.NotifyJson.string
import dev.or2.central.notify.PgNotifyChannel
import dev.or2.central.notify.PgNotifyHandler
import dev.or2.central.worldlink.protocol.packets.outgoing.impl.ServerRightsUpdatePacket
import org.slf4j.LoggerFactory

@PgNotifyChannel("account_rights_events")
class AccountRightsNotifyHandler(
    private val broadcaster: NotifyBroadcaster,
) : PgNotifyHandler {
    private val log = LoggerFactory.getLogger(AccountRightsNotifyHandler::class.java)

    override fun handle(payload: String?) {
        val root = parseObject(payload) ?: return
        val accountId = root.long("account_id") ?: return
        val rights = root.string("rights")
        runCatching {
            val worlds = broadcaster.worldsForAccount(accountId)
            broadcaster.push(
                worlds,
                ServerRightsUpdatePacket.encode(ServerRightsUpdatePacket.Payload(accountId, rights)),
                broadcastAll = true,
            )
        }.onFailure {
            log.warn("Rights push failed for {}", accountId, it)
        }
    }
}
