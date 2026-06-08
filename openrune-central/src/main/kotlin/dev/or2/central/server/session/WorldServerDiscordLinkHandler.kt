package dev.or2.central.server.session

import dev.or2.central.discord.DiscordLinkService
import dev.or2.central.discord.DiscordLinkMessenger
import dev.or2.central.server.net.codec.writeGameDiscordLinkInvalidateAck
import dev.or2.central.server.net.codec.writeGameDiscordLinkPendingFail
import dev.or2.central.server.net.codec.writeGameDiscordLinkPendingOk
import dev.or2.central.server.net.protocol.WorldServerOpcodes
import dev.or2.central.util.config.DiscordRuntimeConfig
import org.slf4j.LoggerFactory

class WorldServerDiscordLinkHandler(
    private val linkService: DiscordLinkService,
    private val messenger: DiscordLinkMessenger,
    private val discordConfig: DiscordRuntimeConfig,
) {
    private val log = LoggerFactory.getLogger(WorldServerDiscordLinkHandler::class.java)

    fun handlePending(
        accountId: Int,
        discordUsername: String,
    ): WorldServerHandleResult.Reply {
        if (accountId <= 0 || discordUsername.isBlank()) {
            return fail(WorldServerOpcodes.GAME_DISCORD_LINK_PENDING_FAIL_BAD_FRAME)
        }

        if (linkService.accountDiscordId(accountId) != null) {
            return fail(WorldServerOpcodes.GAME_DISCORD_LINK_PENDING_FAIL_ALREADY_LINKED)
        }

        val discordUserId = linkService.resolveDiscordUserId(discordUsername)
        if (discordUserId == null) {
            log.info(
                "Discord link lookup failed for accountId={} query='{}'",
                accountId,
                discordUsername,
            )
            return fail(WorldServerOpcodes.GAME_DISCORD_LINK_PENDING_FAIL_DISCORD_NOT_FOUND)
        }

        val code =
            runCatching {
                linkService.createGamePending(
                    accountId = accountId,
                    discordUserId = discordUserId,
                )
            }.getOrElse {
                return fail(WorldServerOpcodes.GAME_DISCORD_LINK_PENDING_FAIL_UNAVAILABLE)
            }

        val pending = linkService.findPendingByAccountId(accountId)
        val dmSent =
            if (pending != null && discordConfig.enabled) {
                val codes = linkService.generateCodeGrid(pending.code)
                messenger.sendVerificationDm(discordUserId, codes) { channelId, messageId ->
                    linkService.attachVerificationMessage(discordUserId, channelId, messageId)
                }
                true
            } else {
                false
            }

        return WorldServerHandleResult.Reply(
            writeGameDiscordLinkPendingOk(code = code, dmSent = dmSent),
            closeAfterWrite = true,
        )
    }

    fun handleInvalidate(accountId: Int): WorldServerHandleResult.Reply {
        if (accountId <= 0) {
            return fail(WorldServerOpcodes.GAME_DISCORD_LINK_PENDING_FAIL_BAD_FRAME)
        }
        linkService.invalidatePending(accountId)
        return WorldServerHandleResult.Reply(
            writeGameDiscordLinkInvalidateAck(),
            closeAfterWrite = true,
        )
    }

    private fun fail(reason: Int): WorldServerHandleResult.Reply =
        WorldServerHandleResult.Reply(
            writeGameDiscordLinkPendingFail(reason),
            closeAfterWrite = true,
        )
}
