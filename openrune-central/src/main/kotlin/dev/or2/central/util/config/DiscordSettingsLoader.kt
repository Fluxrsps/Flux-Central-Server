package dev.or2.central.util.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml

private val log = LoggerFactory.getLogger("dev.or2.central.config.discord")

internal const val DISCORD_SETTINGS_FILE_NAME = "discord-settings.yml"

internal data class DiscordSettingsFile(
    val botToken: String = "",
    val guildId: Long = 0L,
    val pendingTtlMinutes: Long = 15L,
    val maxWrongAttempts: Int = 3,
)

internal fun loadDiscordSettingsFile(): DiscordSettingsFile {
    val path = resolveDiscordSettingsPath() ?: return DiscordSettingsFile()
    return runCatching { parseDiscordSettingsFile(path) }
        .onSuccess { log.info("Loaded Discord settings from {}", path.toAbsolutePath().normalize()) }
        .getOrElse { error ->
            log.warn("Failed to load {}: {}", path.toAbsolutePath().normalize(), error.message)
            DiscordSettingsFile()
        }
}

private fun parseDiscordSettingsFile(path: Path): DiscordSettingsFile {
    val raw =
        Files.newInputStream(path).use { ins ->
            Yaml().load<Any?>(ins)
        }
    val flat = flattenYamlNode(raw)
    return DiscordSettingsFile(
        botToken = flat.stringValue("bot-token"),
        guildId = flat.longValue("guild-id"),
        pendingTtlMinutes = flat.longValue("pending-ttl-minutes", 15L).coerceAtLeast(1L),
        maxWrongAttempts = flat.intValue("max-wrong-attempts", 3).coerceAtLeast(1),
    )
}

private fun Map<String, String>.stringValue(key: String): String = this[key]?.trim().orEmpty()

private fun Map<String, String>.longValue(key: String, default: Long = 0L): Long =
    this[key]?.trim()?.toLongOrNull() ?: default

private fun Map<String, String>.intValue(key: String, default: Int): Int =
    this[key]?.trim()?.toIntOrNull() ?: default

private fun resolveDiscordSettingsPath(): Path? {
    System.getenv("OPENRUNE_DISCORD_SETTINGS")?.trim()?.takeIf { it.isNotEmpty() }?.let { explicit ->
        val path = Paths.get(explicit)
        if (Files.isRegularFile(path)) {
            return path
        }
        log.warn("OPENRUNE_DISCORD_SETTINGS points to missing file: {}", explicit)
    }

    val cwdYaml = Paths.get(DISCORD_SETTINGS_FILE_NAME)
    if (Files.isRegularFile(cwdYaml)) {
        return cwdYaml
    }

    jarSiblingDirectory()?.resolve(DISCORD_SETTINGS_FILE_NAME)?.takeIf { Files.isRegularFile(it) }?.let { return it }

    return null
}
