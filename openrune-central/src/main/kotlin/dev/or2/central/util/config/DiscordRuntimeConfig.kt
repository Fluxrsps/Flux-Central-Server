package dev.or2.central.util.config

data class DiscordRuntimeConfig(
    val botToken: String = "",
    val guildId: Long = 0L,
    val buttonPrefix: String = "discordlink:",
    val codeButtonCount: Int = 20,
    val pendingTtlMinutes: Long = 15,
    val maxWrongAttempts: Int = 3,
) {
    val enabled: Boolean
        get() = botToken.isNotBlank()
}

internal fun resolveDiscordRuntimeConfig(): DiscordRuntimeConfig {
    val settings = loadDiscordSettingsFile()
    return DiscordRuntimeConfig(
        botToken = settings.botToken,
        guildId = settings.guildId,
        pendingTtlMinutes = settings.pendingTtlMinutes,
        maxWrongAttempts = settings.maxWrongAttempts,
    )
}
