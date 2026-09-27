package dev.or2.central.exchange

import dev.or2.central.discord.DiscordBotService
import dev.or2.central.exchange.health.AlertEmbedFactory
import dev.or2.central.exchange.health.AlertNotifier
import dev.or2.central.exchange.health.ExchangeAlert
import java.awt.Color
import java.util.concurrent.Executors
import net.dv8tion.jda.api.EmbedBuilder
import org.slf4j.LoggerFactory

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
