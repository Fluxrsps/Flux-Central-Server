package dev.or2.central

import com.zaxxer.hikari.HikariDataSource
import dev.or2.central.account.AccountNameAuthPolicy
import dev.or2.central.account.AccountRepository
import dev.or2.central.account.BadWordIndex
import dev.or2.central.account.PasswordHasher
import dev.or2.central.analytics.OnlineSampleRepository
import dev.or2.central.analytics.OnlineSampler
import dev.or2.central.util.config.CentralRuntimeConfig
import dev.or2.central.util.config.createCentralDataSource
import dev.or2.central.http.CentralHttpContext
import dev.or2.central.http.centralHttpRoutes
import dev.or2.central.server.logging.CentralActivityLogRepository
import dev.or2.central.account.PunishmentService
import dev.or2.central.http.world.WorldKeyVerifier
import dev.or2.central.http.javconfig.JavConfigCache
import dev.or2.central.http.world.WorldListCache
import dev.or2.central.http.world.WorldLoginGateRepository
import dev.or2.central.http.world.WorldRepository
import dev.or2.central.util.config.WorldServerTcpConfig
import dev.or2.central.server.net.WorldServerTcpServer
import dev.or2.central.server.net.push.WorldServerPushChannelRegistry
import dev.or2.central.server.session.WorldServerSessionService
import dev.or2.central.server.session.WorldSessionReaper
import dev.or2.central.server.console.CentralConsoleCommands
import dev.or2.central.server.console.CentralRefreshRegistry
import dev.or2.central.server.console.CentralRefreshStep
import dev.or2.central.server.console.startCentralConsoleReader
import dev.or2.central.server.session.WorldSessionRepository
import dev.or2.central.server.telemetry.WorldServerTelemetry
import java.util.concurrent.atomic.AtomicBoolean
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlinx.serialization.json.Json

fun Application.installOpenRuneCentral(centralConfig: CentralRuntimeConfig) {
    environment.log.info("OpenRune Central PostgreSQL: {}", centralConfig.jdbcUrl)

    if (centralConfig.httpTrustProxy) {
        install(XForwardedHeaders)
    }

    val dataSource: DataSource = createCentralDataSource(centralConfig)

    CentralSchemaBootstrap.apply(dataSource)

    val worldRepository = WorldRepository(dataSource)
    val worldListCache = WorldListCache(worldRepository)
    val javConfigCache = JavConfigCache(centralConfig)
    val accountRepository = AccountRepository(dataSource)
    val passwordHasher = PasswordHasher()
    val sessionRepository = WorldSessionRepository(dataSource)
    val activityLogRepository = CentralActivityLogRepository(dataSource)
    val punishmentService = PunishmentService(dataSource)
    val worldOperationRepository = WorldOperationRepository(dataSource)

    val worldKeyVerifier = WorldKeyVerifier()
    val worldLoginGateRepository = WorldLoginGateRepository(dataSource)

    val badWordIndex = BadWordIndex(centralConfig)
    AccountNameAuthPolicy.preloadWorldLinkDeceptiveFragments()

    val worldServerTelemetry: WorldServerTelemetry = WorldServerTelemetry.None
    val worldServerPushChannelRegistry = WorldServerPushChannelRegistry()

    val worldServerSessionService =
        WorldServerSessionService(
            dataSource = dataSource,
            worldRepository = worldRepository,
            worldKeyVerifier = worldKeyVerifier,
            accountRepository = accountRepository,
            passwordHasher = passwordHasher,
            sessionRepository = sessionRepository,
            worldListCache = worldListCache,
            punishmentService = punishmentService,
            worldOperationRepository = worldOperationRepository,
            worldLoginGateRepository = worldLoginGateRepository,
            telemetry = worldServerTelemetry,
            badWordRoots = { badWordIndex.roots() },
        )

    val worldsLinkPort = centralConfig.worldsLinkPort

    val worldServerTcpExecutor =
        worldsLinkPort?.let {
            ThreadPoolExecutor(
                centralConfig.worldsLinkHandlerThreads,
                centralConfig.worldsLinkHandlerThreads,
                60L,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(centralConfig.worldsLinkHandlerQueueSize),
                { Thread(it, "openrune-worldserver").apply { isDaemon = true } },
                ThreadPoolExecutor.CallerRunsPolicy(),
            )
        }

    val worldServerTcpServer =
        worldsLinkPort?.let {
            WorldServerTcpServer(
                port = it,
                sessionService = worldServerSessionService,
                executor = worldServerTcpExecutor!!,
                pushChannelRegistry = worldServerPushChannelRegistry,
                worldOperationRepository = worldOperationRepository,
                config = WorldServerTcpConfig(
                    soBacklog = centralConfig.worldsLinkSoBacklog,
                    readTimeoutSeconds = centralConfig.worldsLinkReadTimeoutSeconds,
                    maxConnectionsPerIp = centralConfig.worldsLinkMaxConnectionsPerIp,
                    maxConnectionsTotal = centralConfig.worldsLinkMaxConnectionsTotal,
                    maxFramesPerSecond = centralConfig.worldsLinkMaxFramesPerSecond.toDouble(),
                    maxFrameBurst = centralConfig.worldsLinkMaxFrameBurst.toDouble(),
                )
            )
        }

    val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor {
            Thread(it, "openrune-central-scheduler").apply { isDaemon = true }
        }

    val staleSessionSweeper =
        WorldSessionReaper(
            sessionRepository = sessionRepository,
            worldListCache = worldListCache,
            ttlMillis = centralConfig.sessionsTtlMillis,
            scheduler = scheduler,
        )

    val punishmentPgListener =
        PostgresEventListenerService(
            dataSource = dataSource,
            worldServerPushChannelRegistry = worldServerPushChannelRegistry,
            sessionRepository = sessionRepository,
            worldListCache = worldListCache,
        )

    val onlineSampleRepository = OnlineSampleRepository(dataSource)
    val onlineSampler = OnlineSampler(sessionRepository, onlineSampleRepository)

    val shutdownOnce = AtomicBoolean(false)
    val shutdownCentral: () -> Unit = {
        if (shutdownOnce.compareAndSet(false, true)) {
            punishmentPgListener.stop()
            worldServerTcpServer?.stop()
            worldServerTcpExecutor?.shutdown()
            scheduler.shutdown()
            (dataSource as HikariDataSource).close()
        }
    }

    monitor.subscribe(ApplicationStarted) {
        staleSessionSweeper.start()

        try {
            badWordIndex.refresh()
        } catch (e: Exception) {
            environment.log.warn("account-name bad words initial refresh failed: {}", e.message)
        }

        try {
            javConfigCache.refresh()
        } catch (e: Exception) {
            environment.log.warn("jav_config initial refresh failed: {}", e.message)
        }

        worldServerTcpServer?.start()
        punishmentPgListener.start()
        val badWordsEveryMin = centralConfig.badWordsRefreshMinutes.toLong().coerceAtLeast(5L)
        scheduler.scheduleAtFixedRate(
            {
                try {
                    badWordIndex.refresh()
                } catch (e: Exception) {
                    environment.log.debug("account-name bad words refresh failed: {}", e.message)
                }
            },
            badWordsEveryMin,
            badWordsEveryMin,
            TimeUnit.MINUTES,
        )

        val sampleEverySec =
            centralConfig.onlineSampleIntervalSeconds
                .toLong()
                .coerceAtLeast(30L)

        scheduler.scheduleAtFixedRate(
            {
                try {
                    onlineSampler.sampleNow()
                } catch (e: Exception) {
                    environment.log.debug("online sample failed: {}", e.message)
                }
            },
            sampleEverySec,
            sampleEverySec,
            TimeUnit.SECONDS,
        )

        scheduler.scheduleAtFixedRate(
            {
                try {
                    worldListCache.rebuild()
                } catch (e: Exception) {
                    environment.log.debug("worlds list cache rebuild failed: {}", e.message)
                }
            },
            45L,
            45L,
            TimeUnit.SECONDS,
        )

        val javEveryMin = centralConfig.javConfigRefreshMinutes.toLong().coerceAtLeast(1L)
        scheduler.scheduleAtFixedRate(
            {
                try {
                    javConfigCache.refresh()
                } catch (e: Exception) {
                    environment.log.debug("jav_config refresh failed: {}", e.message)
                }
            },
            javEveryMin,
            javEveryMin,
            TimeUnit.MINUTES,
        )

        val refreshRegistry =
            CentralRefreshRegistry(
                listOf(
                    CentralRefreshStep(
                        keys = setOf("worlds", "worldslist", "worldlist"),
                        displayName = "worlds list",
                        block = { worldListCache.rebuild() },
                    ),
                    CentralRefreshStep(
                        keys = setOf("jav", "javconfig"),
                        displayName = "jav_config",
                        block = { javConfigCache.refresh() },
                    ),
                    CentralRefreshStep(
                        keys = setOf("badwords", "badword"),
                        displayName = "bad words",
                        block = { badWordIndex.refresh() },
                    ),
                    CentralRefreshStep(
                        keys = setOf("online", "onlineplayers", "players"),
                        displayName = "online players",
                        block = { onlineSampler.sampleNow() },
                    ),
                ),
            )

        startCentralConsoleReader(
            CentralConsoleCommands(
                refreshRegistry = refreshRegistry,
                shutdown = shutdownCentral,
            ),
        )

        environment.log.info("OpenRune Central is online")
        environment.log.info("Console: type !help for commands (stop, refresh, …)")
    }

    monitor.subscribe(ApplicationStopped) {
        shutdownCentral()
    }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.application.environment.log.warn("Bad request: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest)
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respondText(
                "Internal Server Error",
                ContentType.Text.Plain,
                status = HttpStatusCode.InternalServerError,
            )
        }
    }

    routing {
        centralHttpRoutes(
            CentralHttpContext(
                sessionRepository = sessionRepository,
                activityLogRepository = activityLogRepository,
                worldListCache = worldListCache,
                javConfigCache = javConfigCache,
                badWordIndex = badWordIndex,
            ),
        )
    }
}
