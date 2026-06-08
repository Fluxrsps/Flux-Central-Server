package dev.or2.central.discord

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import org.slf4j.LoggerFactory

class DiscordLinkMessenger(
    private val buttonPrefix: String,
) {
    private val log = LoggerFactory.getLogger(DiscordLinkMessenger::class.java)
    private var jda: JDA? = null

    fun bind(jda: JDA) {
        this.jda = jda
    }

    fun sendVerificationDm(
        discordUserId: Long,
        codes: List<Int>,
        onSent: (channelId: Long, messageId: Long) -> Unit,
    ) {
        val bot = jda ?: return
        val rows =
            codes.chunked(5).map { rowCodes ->
                ActionRow.of(
                    rowCodes.map { code ->
                        Button.primary("$buttonPrefix$code", code.toString())
                    },
                )
            }

        bot
            .retrieveUserById(discordUserId)
            .queue(
                { user ->
                    user
                        .openPrivateChannel()
                        .queue(
                            { channel ->
                                channel
                                    .sendMessage("Please select the button with the correct code..")
                                    .setComponents(rows)
                                    .queue(
                                        { message -> onSent(channel.idLong, message.idLong) },
                                        { error ->
                                            log.warn(
                                                "Could not send Discord verification DM to user={}",
                                                discordUserId,
                                                error,
                                            )
                                        },
                                    )
                            },
                            { error ->
                                log.warn(
                                    "Could not open Discord DM channel for user={}",
                                    discordUserId,
                                    error,
                                )
                            },
                        )
                },
                { error -> log.warn("Could not resolve Discord user id={}", discordUserId, error) },
            )
    }

    fun deleteVerificationMessage(channelId: Long, messageId: Long) {
        val bot = jda ?: return
        val channel =
            bot.getChannelById(
                net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel::class.java,
                channelId,
            )
        channel
            ?.deleteMessageById(messageId)
            ?.queue(
                {},
                { error ->
                    log.warn(
                        "Could not delete Discord verification message channelId={} messageId={}",
                        channelId,
                        messageId,
                        error,
                    )
                },
            )
    }
}
