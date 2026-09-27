package dev.or2.central.discord

import dev.or2.central.config.DiscordRuntimeConfig
import dev.or2.central.db.repositories.SessionRepository
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.api.utils.MemberCachePolicy
import org.slf4j.LoggerFactory

class DiscordBotService(
    private val config: DiscordRuntimeConfig,
    private val sessionRepository: SessionRepository,
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
        Thread(
            {
                runCatching { startBotBlocking() }
                    .onFailure { error -> log.warn("Discord bot failed to start: {}", error.message, error) }
            },
            "discord-bot-start",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun startBotBlocking() {
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

    /**
     * Posts an embed to a channel, if the bot is connected and can see it.
     *
     * Reports failure rather than throwing: callers here are announcing something already
     * recorded elsewhere, so a Discord outage must not take them down with it. The send is queued,
     * so this does not block the caller.
     */
    fun sendEmbed(channelId: Long, embed: MessageEmbed): Boolean {
        if (channelId <= 0L) {
            return false
        }

        val channel = jda?.getTextChannelById(channelId)

        if (channel == null) {
            log.warn("Discord channel {} unavailable; message dropped", channelId)
            return false
        }

        return runCatching {
            channel.sendMessageEmbeds(embed).queue(
                null,
                { error -> log.warn("Discord message to {} failed: {}", channelId, error.message) },
            )
        }.onFailure { log.warn("Discord message to {} could not be queued", channelId, it) }
            .isSuccess
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
