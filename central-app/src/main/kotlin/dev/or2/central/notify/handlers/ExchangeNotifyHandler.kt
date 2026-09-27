package dev.or2.central.notify.handlers

import dev.or2.central.exchange.ExchangeEngine
import dev.or2.central.notify.NotifyBroadcaster
import dev.or2.central.notify.NotifyJson.int
import dev.or2.central.notify.NotifyJson.long
import dev.or2.central.notify.NotifyJson.parseObject
import dev.or2.central.notify.PgNotifyChannel
import dev.or2.central.notify.PgNotifyHandler
import dev.or2.central.social.OnlinePresenceIndex
import dev.or2.central.worldlink.protocol.packets.outgoing.impl.ServerExchangeNotifyPacket
import org.slf4j.LoggerFactory

/**
 * Pushes a new Trading Post notification to the world its character is on and marks it delivered.
 * Characters who are offline are left for the world to deliver at their next login.
 */
@PgNotifyChannel("exchange_notifications")
class ExchangeNotifyHandler(
    private val broadcaster: NotifyBroadcaster,
    private val presence: OnlinePresenceIndex,
    private val engine: ExchangeEngine,
) : PgNotifyHandler {
    private val log = LoggerFactory.getLogger(ExchangeNotifyHandler::class.java)

    override fun handle(payload: String?) {
        val root = parseObject(payload) ?: return
        val id = root.long("id") ?: return
        val characterId = root.int("character_id") ?: return
        val worldId = presence.worldFor(characterId) ?: return
        runCatching {
            val notification = engine.findNotification(id) ?: return
            if (notification.deliveredAt != null) return
            val frame =
                ServerExchangeNotifyPacket.encode(
                    ServerExchangeNotifyPacket.Payload(
                        characterId = characterId,
                        notificationId = notification.id,
                        kind = notification.kind,
                        orderId = notification.orderId ?: 0L,
                        objId = notification.objId ?: 0,
                        payloadJson = notification.payload,
                    ),
                )
            broadcaster.push(listOf(worldId), frame)
            engine.markNotificationsDelivered(listOf(id), worldId)
        }.onFailure { log.warn("exchange notification {} push failed", id, it) }
    }
}
