package dev.or2.central.server.console

/** Named cache refresh steps for console `refresh` / `refreshcache` commands. */
class CentralRefreshRegistry(
    private val steps: List<CentralRefreshStep>,
) {
    private val stepsByKey: Map<String, CentralRefreshStep> =
        steps
            .flatMap { step -> step.keys.map { key -> key to step } }
            .toMap()

    fun runAll(): List<RefreshStepResult> = steps.map { it.run() }

    fun run(keys: Collection<String>): List<RefreshStepResult> {
        val resolved = LinkedHashSet<CentralRefreshStep>()
        for (key in keys) {
            val step =
                stepsByKey[key]
                    ?: throw IllegalArgumentException("Unknown refresh target: $key")
            resolved.add(step)
        }
        return resolved.map { it.run() }
    }
}

data class CentralRefreshStep(
    val keys: Set<String>,
    val displayName: String,
    val block: () -> Unit,
) {
    fun run(): RefreshStepResult = refreshStep(displayName, block)
}

enum class RefreshScope {
    ALL,
    WORLDS,
    JAV_CONFIG,
    BAD_WORDS,
    ONLINE_PLAYERS,
}

/** Parses `refresh …` / `refreshcache …` targets (tokens after the command word). */
fun parseRefreshScope(argumentTokens: List<String>): RefreshScope {
    if (argumentTokens.isEmpty()) {
        return RefreshScope.ALL
    }

    val target =
        argumentTokens
            .joinToString("")
            .lowercase()
            .replace("_", "")
            .replace("-", "")

    return when (target) {
        "all",
        "everything",
        "cache",
        "caches" -> RefreshScope.ALL
        "worlds",
        "worldslist",
        "worldlist",
        "world" -> RefreshScope.WORLDS
        "jav",
        "javconfig",
        "config" -> RefreshScope.JAV_CONFIG
        "badwords",
        "badword",
        "profanity",
        "words" -> RefreshScope.BAD_WORDS
        "online",
        "onlineplayers",
        "onlineplayer",
        "players",
        "player",
        "population" -> RefreshScope.ONLINE_PLAYERS
        else -> throw IllegalArgumentException("Unknown refresh target: ${argumentTokens.joinToString(" ")}")
    }
}

fun RefreshScope.toRegistryKeys(): Set<String> =
    when (this) {
        RefreshScope.ALL -> emptySet()
        RefreshScope.WORLDS -> setOf("worlds")
        RefreshScope.JAV_CONFIG -> setOf("jav")
        RefreshScope.BAD_WORDS -> setOf("badwords")
        RefreshScope.ONLINE_PLAYERS -> setOf("online")
    }
