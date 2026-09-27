package dev.or2.central.exchange

import dev.or2.central.discord.DiscordBotService
import dev.or2.central.exchange.health.AlertEmbedFactory
import dev.or2.central.exchange.health.AlertNotifier
import dev.or2.central.exchange.health.ExchangeAlert
import java.awt.Color
import java.util.concurrent.Executors
import net.dv8tion.jda.api.EmbedBuilder
import org.slf4j.LoggerFactory

/**
 * Announces new Trading Post alerts through the Discord bot Central already runs.
 *
 * Uses the bot rather than a webhook: the connection, token and guild are configured once for
 * everything Central says on Discord, and a webhook would have been a second credential to keep
 * and rotate for no extra reach.
 *
 * Alerts are built and sent on their own thread. The alert row is the record of what happened, so
 * a slow or unreachable Discord is logged and dropped rather than allowed to hold up a job.
 */
class DiscordAlertNotifier(
    private val bot: DiscordBotService,
    private val channelId: Long,
    private val embeds: AlertEmbedFactory = AlertEmbedFactory(),
) : AlertNotifier {
    private val log = LoggerFactory.getLogger(DiscordAlertNotifier::class.java)

    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "exchange-alert-discord").apply { isDaemon = true }
        }

    override fun notify(alert: ExchangeAlert) {
        if (channelId <= 0L) {
            return
        }

        executor.execute {
            runCatching { bot.sendEmbed(channelId, render(alert)) }
                .onFailure { log.warn("could not announce alert #{}", alert.id, it) }
        }
    }

    private fun render(alert: ExchangeAlert) =
        embeds.build(alert).let { embed ->
            val builder =
                EmbedBuilder()
                    .setTitle(embed.title)
                    .setDescription(embed.description)
                    .setColor(Color(embed.color))
                    .setFooter(embed.footer)
                    .setTimestamp(embed.timestamp)

            embed.thumbnailUrl?.let(builder::setThumbnail)
            embed.fields.forEach { builder.addField(it.name, it.value, it.inline) }

            builder.build()
        }
}
