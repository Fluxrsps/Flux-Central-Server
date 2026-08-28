package dev.or2.central.worldlink

import dev.or2.central.worldlink.handlers.HeartbeatHandler
import dev.or2.central.worldlink.handlers.HelloHandler
import dev.or2.central.worldlink.handlers.HandlerResult
import dev.or2.central.worldlink.handlers.LoginHandler
import dev.or2.central.worldlink.handlers.LogoutHandler
import dev.or2.central.worldlink.handlers.PushSubscribeHandler
import dev.or2.central.worldlink.protocol.PacketCatalog
import dev.or2.central.worldlink.protocol.WorldOpcodes
import dev.or2.central.worldlink.handlers.DiscordLinkHandler
import dev.or2.central.worldlink.handlers.LayoutHandler
import dev.or2.central.worldlink.handlers.SocialHandler
import dev.or2.central.worldlink.protocol.packets.outgoing.impl.HelloRejectPacket
import dev.or2.central.worldlink.protocol.packets.outgoing.impl.LoginFailPacket
import dev.or2.central.worldlink.protocol.readInboundFrame
import io.netty.buffer.ByteBuf

class WorldLinkHandler(
    private val helloHandler: HelloHandler,
    private val loginHandler: LoginHandler,
    private val pushSubscribeHandler: PushSubscribeHandler,
    private val heartbeatHandler: HeartbeatHandler,
    private val logoutHandler: LogoutHandler,
    private val socialHandler: SocialHandler,
    private val layoutHandler: LayoutHandler,
    private val discordLinkHandler: DiscordLinkHandler,
    private val registry: WorldConnectionRegistry,
    private val worldPresenceService: WorldPresenceService,
) {
    fun handle(connection: WorldConnection, payload: ByteBuf): HandlerResult {
        if (!payload.isReadable) {
            payload.release()
            return HandlerResult.CloseSilent
        }
        val input = readInboundFrame(payload)
        try {
            val shapeErr = PacketCatalog.validateInboundBody(input.opcode, input.remainingAfterOpcode)
            if (shapeErr != null) return rejectBadShape(connection)
            return when (input.opcode) {
                WorldOpcodes.OP_WORLD_HELLO -> helloHandler.handle(connection, input)
                WorldOpcodes.OP_LOGIN -> loginHandler.handle(connection, input)
                WorldOpcodes.OP_PUSH_SUBSCRIBE -> {
                    val result = pushSubscribeHandler.handle(connection, input)
                    if (connection.subscribedForPush) {
                        registry.attach(connection)
                        worldPresenceService.onPushChannelAttached(connection.worldId)
                    }
                    result
                }
                WorldOpcodes.OP_HEARTBEAT -> heartbeatHandler.handle(connection, input)
                WorldOpcodes.OP_LOGOUT -> logoutHandler.handle(connection, input)
                WorldOpcodes.OP_WORLD_PM_RELAY,
                WorldOpcodes.OP_WORLD_FRIEND_ADD,
                WorldOpcodes.OP_WORLD_FRIEND_DEL,
                WorldOpcodes.OP_WORLD_IGNORE_ADD,
                WorldOpcodes.OP_WORLD_IGNORE_DEL,
                WorldOpcodes.OP_WORLD_CHAT_FILTERS,
                WorldOpcodes.OP_WORLD_SOCIAL_SYNC,
                -> socialHandler.handle(connection, input.opcode, input)
                WorldOpcodes.OP_WORLD_LAYOUT_SAVE,
                WorldOpcodes.OP_WORLD_LAYOUT_LOAD,
                WorldOpcodes.OP_WORLD_LAYOUT_LIST,
                WorldOpcodes.OP_WORLD_LAYOUT_SHARED,
                -> layoutHandler.handle(connection, input.opcode, input)
                WorldOpcodes.OP_GAME_DISCORD_LINK_PENDING ->
                    discordLinkHandler.handlePending(connection, input)
                WorldOpcodes.OP_GAME_DISCORD_LINK_INVALIDATE ->
                    discordLinkHandler.handleInvalidate(connection, input)
                else -> rejectBadShape(connection)
            }
        } finally {
            input.close()
        }
    }

    fun onChannelClosed(connection: WorldConnection) {
        val worldId = connection.worldId
        if (worldId <= 0) {
            return
        }
        val registered = registry.get(worldId) ?: return
        if (registered.channel !== connection.channel) {
            return
        }
        registry.detach(worldId)
        // Push channels reconnect routinely; do not treat a dropped push socket as the world going offline.
        if (!connection.subscribedForPush) {
            worldPresenceService.onWorldDisconnected(worldId)
        }
    }

    private fun rejectBadShape(connection: WorldConnection): HandlerResult =
        if (!connection.handshakeDone) {
            HandlerResult.Reply(HelloRejectPacket.encode(HelloRejectPacket.Payload(WorldOpcodes.HELLO_REASON_PROTOCOL)), true)
        } else {
            HandlerResult.Reply(LoginFailPacket.encode(LoginFailPacket.Payload(WorldOpcodes.LOGIN_FAIL_BAD_FRAME)))
        }
}
