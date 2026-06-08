package dev.or2.central.discord

import dev.or2.central.server.session.WorldSessionRepository
import dev.or2.central.util.config.DiscordRuntimeConfig
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.api.utils.MemberCachePolicy
import org.slf4j.LoggerFactory

class DiscordBotService(
    private val config: DiscordRuntimeConfig,
    private val sessionRepository: WorldSessionRepository,
    private val interactionListener: DiscordInteractionListener,
    private val guildMembers: DiscordGuildMembers,
    private val linkMessenger: DiscordLinkMessenger,
) {
    private val log = LoggerFactory.getLogger(DiscordBotService::class.java)
    private var jda: JDA? = null
    private val statusExecutor =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "discord-bot-status").apply { isDaemon = true }
        }

    fun start() {
        if (!config.enabled) {
            log.info("Discord bot disabled (no bot token configured)")
            return
        }

        log.info("Starting Discord bot for guild {}...", config.guildId)
        val bot =
            JDABuilder
                .createDefault(config.botToken, GatewayIntent.GUILD_MEMBERS)
                .disableCache(
                    CacheFlag.VOICE_STATE,
                    CacheFlag.EMOJI,
                    CacheFlag.STICKER,
                    CacheFlag.SCHEDULED_EVENTS,
                )
                .setMemberCachePolicy(MemberCachePolicy.ONLINE)
                .addEventListeners(interactionListener, guildMembers)
                .build()
        bot.awaitReady()
        registerSlashCommands(bot)
        guildMembers.bind(bot)
        guildMembers.seedCachedMembers(bot)
        guildMembers.preloadAllMembersAsync(bot)
        linkMessenger.bind(bot)
        jda = bot
        updatePresence(bot)
        statusExecutor.scheduleAtFixedRate(
            { runCatching { updatePresence(bot) } },
            30,
            30,
            TimeUnit.SECONDS,
        )
        log.info("Discord bot logged in as {}", bot.selfUser.name)
    }

    fun stop() {
        statusExecutor.shutdownNow()
        val bot = jda ?: return
        log.info("Shutting down Discord bot...")
        bot.shutdown()
        jda = null
    }

    private fun registerSlashCommands(bot: JDA) {
        val guild =
            bot.getGuildById(config.guildId) ?: run {
                log.warn("Discord guild {} not found; slash commands not registered", config.guildId)
                return
            }
        guild
            .updateCommands()
            .addCommands(
                Commands
                    .slash(DiscordInteractionListener.STATS_COMMAND, "View a player's in-game stats")
                    .addOption(OptionType.STRING, "username", "Character or account name", false),
            )
            .queue(
                { log.info("Registered Discord slash commands for guild {}", config.guildId) },
                { error -> log.warn("Failed to register Discord slash commands: {}", error.message) },
            )
    }

    private fun updatePresence(bot: JDA) {
        val online = sessionRepository.totalOnline()
        val activity =
            if (online == 1) {
                Activity.playing("$online player online")
            } else {
                Activity.playing("$online players online")
            }
        bot.presence.setPresence(OnlineStatus.ONLINE, activity)
    }
}
