package dev.or2.central.util.config

internal const val DEFAULT_DISCORD_BOT_TOKEN: String =
    "ODM3MTcyNDc0MzM1OTg1NzA0.Gl7xxl.mcdh_hQHEWBLD2wtxjgpQI7dCfnimAz0Bpd6jU"

internal const val DEFAULT_DISCORD_GUILD_ID: Long = 1488892413836660746L

data class DiscordRuntimeConfig(
    val botToken: String = DEFAULT_DISCORD_BOT_TOKEN,
    val guildId: Long = DEFAULT_DISCORD_GUILD_ID,
    val buttonPrefix: String = "discordlink:",
    val codeButtonCount: Int = 20,
    val pendingTtlMinutes: Long = 15,
    val maxWrongAttempts: Int = 3,
) {
    val enabled: Boolean
        get() = botToken.isNotBlank()
}

internal fun resolveDiscordRuntimeConfig(cfg: CentralMergedConfig): DiscordRuntimeConfig {
    val token =
        cfg.optionalString(CentralConfigKey.DISCORD_BOT_TOKEN).orEmpty().trim().ifBlank {
            DEFAULT_DISCORD_BOT_TOKEN
        }
    val configuredGuildId = cfg.long(CentralConfigKey.DISCORD_GUILD_ID, 0L)
    val guildId = if (configuredGuildId != 0L) configuredGuildId else DEFAULT_DISCORD_GUILD_ID
    return DiscordRuntimeConfig(
        botToken = token,
        guildId = guildId,
        pendingTtlMinutes = cfg.long(CentralConfigKey.DISCORD_PENDING_TTL_MINUTES, 15L).coerceAtLeast(1L),
        maxWrongAttempts = cfg.int(CentralConfigKey.DISCORD_MAX_WRONG_ATTEMPTS, 3).coerceAtLeast(1),
    )
}
