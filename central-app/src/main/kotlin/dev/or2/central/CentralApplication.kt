package dev.or2.central

import com.zaxxer.hikari.HikariDataSource
import dev.or2.central.account.AccountNameAuthPolicy
import dev.or2.central.account.BadWordIndex
import dev.or2.central.analytics.OnlineSampler
import dev.or2.central.auth.PasswordAuthConfig
import dev.or2.central.auth.PasswordHasher
import dev.or2.central.config.CentralConfig
import dev.or2.central.config.toPasswordAuthConfig
import dev.or2.central.config.resolveDiscordRuntimeConfig
import dev.or2.central.discord.DiscordRuntime
import dev.or2.central.config.DataSourceFactory
import dev.or2.central.db.DevWorldSeeder
import dev.or2.central.db.FlywayMigrator
import dev.or2.central.db.repositories.AccountRepository
import dev.or2.central.db.repositories.OnlineSampleRepository
import dev.or2.central.db.repositories.PunishmentService
import dev.or2.central.db.repositories.SessionRepository
import dev.or2.central.db.repositories.WorldOperationRepository
import dev.or2.central.db.repositories.WorldRepository
import dev.or2.central.logs.CentralActivityLogPublisher
import dev.or2.central.logs.CentralActivityLogRepository
import dev.or2.central.logs.DefaultCentralActivityLogPublisher
import dev.or2.central.http.JavConfigCache
import dev.or2.central.http.WorldKeyVerifier
import dev.or2.central.http.WorldListCache
import dev.or2.central.http.centralHttpRoutes
import dev.or2.central.notify.NotifyBroadcaster
import dev.or2.central.notify.PgNotifyDiscovery
import dev.or2.central.notify.PgNotifyRegistrar
import dev.or2.central.notify.PgNotifyService
import dev.or2.central.notify.handlers.AccountDiscordIdNotifyHandler
import dev.or2.central.notify.handlers.CharacterDisplayNameNotifyHandler
import dev.or2.central.notify.handlers.CharacterMuteNotifyHandler
import dev.or2.central.notify.handlers.PunishmentKickNotifyHandler
import dev.or2.central.notify.handlers.PunishmentNotifyHandler
import dev.or2.central.notify.handlers.WorldBroadcastNotifyHandler
import dev.or2.central.notify.handlers.WorldListInvalidateHandler
import dev.or2.central.notify.handlers.WorldRebootNotifyHandler
import dev.or2.central.layout.CentralLayoutRepository
import dev.or2.central.layout.LayoutService
import dev.or2.central.social.CentralSocialRepository
import dev.or2.central.social.OnlinePresenceIndex
import dev.or2.central.social.SocialGraphLoader
import dev.or2.central.social.SocialGraphPersistence
import dev.or2.central.social.SocialGraphStore
import dev.or2.central.social.SocialPresenceResolver
import dev.or2.central.social.SocialService
import dev.or2.central.session.SessionReaper
import dev.or2.central.session.SessionService
import dev.or2.central.worldlink.WorldConnectionRegistry
import dev.or2.central.worldlink.WorldLinkHandler
import dev.or2.central.worldlink.WorldPresenceService
import dev.or2.central.worldlink.handlers.HeartbeatHandler
import dev.or2.central.worldlink.handlers.HelloHandler
import dev.or2.central.worldlink.handlers.LoginHandler
import dev.or2.central.worldlink.handlers.LogoutHandler
import dev.or2.central.worldlink.handlers.PushSubscribeHandler
import dev.or2.central.worldlink.handlers.DiscordLinkHandler
import dev.or2.central.worldlink.handlers.LayoutHandler
import dev.or2.central.worldlink.handlers.SocialHandler
import dev.or2.central.worldlink.net.WorldLinkServer
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.routing
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin

object CentralApplication {
    fun configure(application: Application, config: CentralConfig) {
        AccountNameAuthPolicy.preloadWorldLinkDeceptiveFragments()

        val dataSource = DataSourceFactory.create(config)
        FlywayMigrator.migrate(dataSource)
        DevWorldSeeder.seedIfEnabled(config, dataSource)

        application.install(Koin) {
            modules(centralModule(config, dataSource))
        }

        if (config.http.trustProxy) {
            application.install(XForwardedHeaders)
        }

        application.install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }

        val koin = application.getKoin()
        val worldListCache = koin.get<WorldListCache>()
        val javConfigCache = koin.get<JavConfigCache>()

        application.routing {
            centralHttpRoutes(worldListCache, javConfigCache)
        }

        val shutdownOnce = AtomicBoolean(false)
        val shutdown: () -> Unit = {
            if (shutdownOnce.compareAndSet(false, true)) {
                runCatching { koin.get<SocialGraphPersistence>().flush() }
                runCatching { koin.get<DiscordRuntime.Components>().botService.stop() }
                koin.get<PgNotifyService>().stop()
                koin.get<WorldLinkServer>().stop()
                koin.get<java.util.concurrent.ScheduledExecutorService>().shutdown()
                (koin.get<DataSource>() as HikariDataSource).close()
            }
        }

        application.monitor.subscribe(ApplicationStarted) {
            koin.get<SessionReaper>().start()
            koin.get<WorldLinkServer>().start()
            koin.get<PgNotifyService>().start()
            runCatching { koin.get<DiscordRuntime.Components>().botService.start() }
            runCatching { worldListCache.rebuild() }
            runCatching { javConfigCache.refresh() }
            val scheduler = koin.get<java.util.concurrent.ScheduledExecutorService>()
            val sampleSec = config.analytics.onlineSampleIntervalSeconds.toLong().coerceAtLeast(30)
            scheduler.scheduleAtFixedRate(
                { runCatching { koin.get<OnlineSampler>().sampleNow() } },
                sampleSec,
                sampleSec,
                TimeUnit.SECONDS,
            )
            scheduler.scheduleAtFixedRate(
                { runCatching { worldListCache.rebuild() } },
                45,
                45,
                TimeUnit.SECONDS,
            )
            val javMin = config.javConfig.refreshMinutes.toLong().coerceAtLeast(1)
            scheduler.scheduleAtFixedRate(
                { runCatching { javConfigCache.refresh() } },
                javMin,
                javMin,
                TimeUnit.MINUTES,
            )
            val badMin = config.badWords.refreshMinutes.toLong().coerceAtLeast(1)
            scheduler.scheduleAtFixedRate(
                { runCatching { koin.get<BadWordIndex>().refresh() } },
                badMin,
                badMin,
                TimeUnit.MINUTES,
            )
            application.environment.log.info(
                "{} Central is online (http={}, world-link={})",
                config.serverName,
                config.http.port,
                config.worldLink.port,
            )
        }

        application.monitor.subscribe(ApplicationStopped) {
            shutdown()
        }
    }

    private fun centralModule(config: CentralConfig, dataSource: DataSource) =
        module {
            single { config }
            single { resolveDiscordRuntimeConfig() }
            single<DataSource> { dataSource }
            single { config.auth.toPasswordAuthConfig() }
            single {
                get<PasswordAuthConfig>().toHasher() as PasswordHasher
            }
            single { BadWordIndex(get()) }
            single { AccountRepository(get()) }
            single { SessionRepository(get()) }
            single { WorldRepository(get()) }
            single { PunishmentService(get()) }
            single { WorldOperationRepository(get()) }
            single { OnlineSampleRepository(get()) }
            single { CentralActivityLogRepository(get()) }
            single<CentralActivityLogPublisher> { DefaultCentralActivityLogPublisher(get()) }
            single { WorldKeyVerifier() }
            single { WorldListCache(get()) }
            single { JavConfigCache(config) }
            single { WorldConnectionRegistry() }
            single { WorldPresenceService(get(), get(), get(), get()) }
            single { CentralSocialRepository(get()) }
            single { CentralLayoutRepository(get()) }
            single { LayoutService(get()) }
            single {
                val store = SocialGraphStore()
                SocialGraphLoader(get()).loadInto(store)
                store
            }
            single { SocialGraphPersistence(get()) }
            single {
                val index = OnlinePresenceIndex()
                val activeSince = System.currentTimeMillis() - config.session.ttlMs.coerceAtLeast(1L)
                index.hydrate(get<SessionRepository>().listActiveOnlineCharacters(activeSince))
                index
            }
            single {
                SocialPresenceResolver(
                    get(),
                    get(),
                    get(),
                    config.session.ttlMs,
                )
            }
            single {
                SocialService(
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    config.diagnostics.socialPmTraceLogs,
                )
            }
            single {
                SessionService(
                    dataSource = get(),
                    accountRepository = get(),
                    sessionRepository = get(),
                    punishmentService = get(),
                    worldRepository = get(),
                    worldOperationRepository = get(),
                    passwordHasher = get(),
                    worldListCache = get(),
                    socialService = get(),
                    badWordRoots = { get<BadWordIndex>().roots() },
                    loginTimingLogs = config.diagnostics.loginTimingLogs,
                )
            }
            single { HelloHandler(get(), get(), get()) }
            single { LoginHandler(get(), get()) }
            single { PushSubscribeHandler() }
            single { HeartbeatHandler(get()) }
            single { LogoutHandler(get(), get(), get()) }
            single { SocialHandler(get()) }
            single { LayoutHandler(get()) }
            single {
                DiscordRuntime.create(
                    dataSource = get(),
                    config = get(),
                    sessionRepository = get(),
                )
            }
            single {
                DiscordLinkHandler(
                    linkService = get<DiscordRuntime.Components>().linkService,
                    messenger = get<DiscordRuntime.Components>().linkMessenger,
                    discordConfig = get(),
                )
            }
            single {
                WorldLinkHandler(
                    helloHandler = get(),
                    loginHandler = get(),
                    pushSubscribeHandler = get(),
                    heartbeatHandler = get(),
                    logoutHandler = get(),
                    socialHandler = get(),
                    layoutHandler = get(),
                    discordLinkHandler = get(),
                    registry = get(),
                    worldPresenceService = get(),
                )
            }
            single { WorldLinkServer(config.worldLink, get(), get()) }
            single { NotifyBroadcaster(get(), get(), get(), get()) }
            single { PunishmentNotifyHandler(get()) }
            single { PunishmentKickNotifyHandler(get()) }
            single { CharacterMuteNotifyHandler(get()) }
            single { WorldRebootNotifyHandler(get()) }
            single { WorldBroadcastNotifyHandler(get()) }
            single { WorldListInvalidateHandler(get()) }
            single { CharacterDisplayNameNotifyHandler(get(), get()) }
            single { AccountDiscordIdNotifyHandler(get()) }
            single {
                val koin = getKoin()
                PgNotifyService(
                    dataSource = get(),
                    handlers =
                        PgNotifyRegistrar.register(
                            PgNotifyDiscovery.all().map { kClass -> koin.get(kClass) },
                        ),
                )
            }
            single { OnlineSampler(get(), get()) }
            single {
                Executors.newSingleThreadScheduledExecutor {
                    Thread(it, "central-scheduler").apply { isDaemon = true }
                }
            }
            single {
                SessionReaper(
                    sessionRepository = get(),
                    socialService = get(),
                    worldListCache = get(),
                    ttlMillis = config.session.ttlMs,
                    scheduler = get(),
                )
            }
        }
}
