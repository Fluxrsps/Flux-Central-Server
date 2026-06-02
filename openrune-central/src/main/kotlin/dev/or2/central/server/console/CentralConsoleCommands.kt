package dev.or2.central.server.console

import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.or2.central.console")

class CentralConsoleCommands(
    private val refreshRegistry: CentralRefreshRegistry,
    private val shutdown: () -> Unit,
) {
    private val stopping = AtomicBoolean(false)

    fun handleLine(line: String) {
        val command = line.trim()
        if (command.isEmpty()) return

        val normalized = command.removePrefix("!").trim().lowercase()
        val tokens = normalized.split(Regex("""\s+""")).filter { it.isNotEmpty() }

        when (tokens.firstOrNull()) {
            "help" -> printHelp()
            "stop" -> requestStop()
            "refreshcache" -> runRefresh(parseRefreshScope(tokens.drop(1)))
            "refresh" -> runRefresh(parseRefreshScope(tokens.drop(1)))
            else ->
                println(
                    "[Console] Unknown command: $command (type !help)",
                )
        }
    }

    private fun printHelp() {
        println(
            """
            |[Console] Commands:
            |  !help                    — list commands
            |  stop                     — shut down Central (also used by the panel)
            |
            |  refreshcache             — reload all caches (same as refresh all)
            |  refresh all              — worlds list, jav_config, bad words, online players
            |  refresh worlds           — worlds list / worlds.js only
            |  refresh javconfig        — jav_config.ws only
            |  refresh badwords         — account bad-word list only
            |  refresh onlineplayers    — online player sample only
            """.trimMargin(),
        )
    }

    private fun requestStop() {
        if (!stopping.compareAndSet(false, true)) {
            println("[Console] Already stopping.")
            return
        }
        println("[Console] Stopping OpenRune Central...")
        try {
            shutdown()
        } catch (e: Exception) {
            log.warn("Shutdown hook failed", e)
        }
        kotlin.system.exitProcess(0)
    }

    private fun runRefresh(scope: RefreshScope) {
        val label =
            when (scope) {
                RefreshScope.ALL -> "all caches"
                RefreshScope.WORLDS -> "worlds list"
                RefreshScope.JAV_CONFIG -> "jav_config"
                RefreshScope.BAD_WORDS -> "bad words"
                RefreshScope.ONLINE_PLAYERS -> "online players"
            }
        println("[Console] Refreshing $label...")

        val results =
            try {
                val keys = scope.toRegistryKeys()
                if (scope == RefreshScope.ALL) {
                    refreshRegistry.runAll()
                } else {
                    refreshRegistry.run(keys)
                }
            } catch (e: IllegalArgumentException) {
                println("[Console] ${e.message}")
                println("[Console] Type !help for refresh targets.")
                return
            }

        var ok = true
        for (step in results) {
            if (step.success) {
                println("[Console]   ${step.name} — ok")
            } else {
                ok = false
                println("[Console]   ${step.name} — failed: ${step.errorMessage}")
                log.warn("Console refresh ({}): {} failed", scope, step.name, step.error)
            }
        }
        if (results.isEmpty()) {
            println("[Console] Nothing to refresh.")
            return
        }
        if (ok) {
            println("[Console] Refresh completed.")
        } else {
            println("[Console] Refresh finished with errors (see above).")
        }
    }
}

data class RefreshStepResult(
    val name: String,
    val success: Boolean,
    val errorMessage: String? = null,
    val error: Throwable? = null,
)

fun refreshStep(name: String, block: () -> Unit): RefreshStepResult =
    try {
        block()
        RefreshStepResult(name, success = true)
    } catch (e: Exception) {
        RefreshStepResult(name, success = false, errorMessage = e.message, error = e)
    }

fun startCentralConsoleReader(commands: CentralConsoleCommands) {
    val thread =
        Thread(
            {
                runCatching {
                    System.`in`.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            runCatching { commands.handleLine(line) }
                                .onFailure { e ->
                                    if (e is IllegalArgumentException) {
                                        println("[Console] ${e.message}")
                                    } else {
                                        log.warn("Console command failed", e)
                                        println("[Console] Error: ${e.message}")
                                    }
                                }
                        }
                    }
                }.onFailure { e ->
                    log.debug("Console reader ended: {}", e.message)
                }
            },
            "openrune-console",
        )
    thread.isDaemon = true
    thread.start()
    log.info("Console commands enabled (!help, stop, refresh / refreshcache)")
}
