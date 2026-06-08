package dev.or2.central.discord

import dev.or2.central.server.session.WorldSessionRepository
import dev.or2.central.util.config.DiscordRuntimeConfig
import javax.sql.DataSource

object DiscordRuntime {
    fun create(
        dataSource: DataSource,
        config: DiscordRuntimeConfig,
        sessionRepository: WorldSessionRepository,
    ): Components {
        val guildMembers = DiscordGuildMembers(config.guildId)
        val linkMessenger = DiscordLinkMessenger(config.buttonPrefix)
        val linkService = DiscordLinkService(dataSource, guildMembers, config)
        val statsService = DiscordStatsService(dataSource)
        val interactionListener =
            DiscordInteractionListener(
                link = linkService,
                messenger = linkMessenger,
                stats = statsService,
                config = config,
            )
        val botService =
            DiscordBotService(
                config = config,
                sessionRepository = sessionRepository,
                interactionListener = interactionListener,
                guildMembers = guildMembers,
                linkMessenger = linkMessenger,
            )
        return Components(
            linkService = linkService,
            linkMessenger = linkMessenger,
            botService = botService,
        )
    }

    data class Components(
        val linkService: DiscordLinkService,
        val linkMessenger: DiscordLinkMessenger,
        val botService: DiscordBotService,
    )
}
