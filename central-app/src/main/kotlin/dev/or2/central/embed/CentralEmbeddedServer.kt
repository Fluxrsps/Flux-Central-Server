package dev.or2.central.embed

import dev.or2.central.CentralApplication
import dev.or2.central.config.CentralConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import org.slf4j.LoggerFactory

class CentralEmbeddedServer(
    private val config: CentralConfig,
) {
    private val log = LoggerFactory.getLogger(CentralEmbeddedServer::class.java)

    @Volatile
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    private var stopEmbeddedPostgresOnStop = false
    private lateinit var effectiveConfig: CentralConfig

    fun start() {
        check(server == null) { "Server already started" }
        val (resolved, startedEmbedded) = EmbeddedPostgres.resolveConfig(config)
        effectiveConfig = resolved
        stopEmbeddedPostgresOnStop = startedEmbedded
        server =
            embeddedServer(Netty, port = effectiveConfig.http.port, host = "0.0.0.0") {
                CentralApplication.configure(this, effectiveConfig)
            }.also { it.start(wait = false) }

        log.info(
            "HTTP listening on 0.0.0.0:{} — world-link on 0.0.0.0:{}",
            effectiveConfig.http.port,
            effectiveConfig.worldLink.port,
        )
        log.info(READY_MARKER)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        server = null
        if (stopEmbeddedPostgresOnStop) {
            EmbeddedPostgres.stop()
            stopEmbeddedPostgresOnStop = false
        }
    }

    companion object {
        /** Must match `config.startup.done` in `deploy/pterodactyl/egg-openrune-central.json`. */
        const val READY_MARKER = "OpenRune Central is online"
    }
}
