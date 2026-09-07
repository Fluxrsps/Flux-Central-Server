package dev.or2.central.config

import com.sksamuel.hoplite.ConfigAlias
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addEnvironmentSource
import com.sksamuel.hoplite.addPathSource
import java.nio.file.Files
import java.nio.file.Path

data class CentralConfig(
    @ConfigAlias("OPENRUNE_SERVER_NAME")
    val serverName: String = "OpenRune",
    val auth: AuthConfig = AuthConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val jdbc: JdbcConfig? = null,
    val http: HttpConfig = HttpConfig(),
    val worldLink: WorldLinkConfig = WorldLinkConfig(),
    val session: SessionConfig = SessionConfig(),
    val analytics: AnalyticsConfig = AnalyticsConfig(),
    val javConfig: JavConfigSettings = JavConfigSettings(),
    val badWords: BadWordsConfig = BadWordsConfig(),
    val devWorld: DevWorldConfig = DevWorldConfig(),
    val diagnostics: DiagnosticsConfig = DiagnosticsConfig(),
) {
    fun resolvedJdbcUrl(): String {
        if (!jdbc?.url.isNullOrBlank()) return jdbc!!.url
        val db = database
        require(db.host.isNotBlank() && db.name.isNotBlank()) {
            "database.host and database.name required when jdbc.url is unset"
        }
        return "jdbc:postgresql://${db.host}:${db.port}/${db.name}"
    }

    fun resolvedDbUser(): String = jdbc?.user?.takeIf { it.isNotBlank() } ?: database.user

    fun resolvedDbPassword(): String = jdbc?.password ?: database.password

    fun loginWelcomeMessage(): String = "Welcome to $serverName."

    companion object {
        fun load(): CentralConfig {
            val explicitPath = System.getenv("OPENRUNE_CONFIG")?.trim()?.takeIf { it.isNotEmpty() }
            return load(
                configPath = Path.of(explicitPath ?: "central-config.yaml"),
                required = explicitPath != null,
            )
        }

        /** Loads [configPath] layered over the environment; the file wins on conflicts. */
        fun load(configPath: Path, required: Boolean): CentralConfig {
            val builder = ConfigLoaderBuilder.default()

            if (Files.isRegularFile(configPath)) {
                builder.addPathSource(configPath)
            } else if (required) {
                error("Config file not found or not readable: ${configPath.toAbsolutePath()}")
            }

            builder.addEnvironmentSource(useUnderscoresAsSeparator = false)

            return builder.build().loadConfigOrThrow<CentralConfigRoot>().openrune
        }
    }
}

/** Root wrapper matching `openrune:` key in YAML. */
data class CentralConfigRoot(
    val openrune: CentralConfig = CentralConfig(),
)

data class AuthConfig(
    @ConfigAlias("OPENRUNE_AUTH_PASSWORD_HASHER")
    val passwordHasher: String = "bcrypt",
    @ConfigAlias("OPENRUNE_AUTH_BCRYPT_COST")
    val bcryptCost: Int = 12,
    /** Argon2 time cost for newly hashed passwords (verify uses params embedded in the stored hash). */
    @ConfigAlias("OPENRUNE_AUTH_ARGON2_ITERATIONS")
    val argon2Iterations: Int = 20,
    @ConfigAlias("OPENRUNE_AUTH_ARGON2_MEMORY_KIB")
    val argon2MemoryKib: Int = 65536,
)

data class DatabaseConfig(
    @ConfigAlias("OPENRUNE_DB_HOST")
    val host: String = "",
    @ConfigAlias("OPENRUNE_DB_PORT")
    val port: Int = 5432,
    @ConfigAlias("OPENRUNE_DB_NAME")
    val name: String = "",
    @ConfigAlias("OPENRUNE_DB_USER")
    val user: String = "postgres",
    @ConfigAlias("OPENRUNE_DB_PASSWORD")
    val password: String = "",
    @ConfigAlias("OPENRUNE_DB_POOL_SIZE")
    val poolSize: Int = 10,
)

data class JdbcConfig(
    @ConfigAlias("OPENRUNE_JDBC_URL")
    val url: String = "",
    @ConfigAlias("OPENRUNE_DB_USER")
    val user: String? = null,
    @ConfigAlias("OPENRUNE_DB_PASSWORD")
    val password: String? = null,
)

data class HttpConfig(
    @ConfigAlias("OPENRUNE_HTTP_PORT")
    val port: Int = 8080,
    @ConfigAlias("OPENRUNE_HTTP_TRUST_PROXY")
    val trustProxy: Boolean = false,
)

data class WorldLinkConfig(
    @ConfigAlias("OPENRUNE_WORLDS_LINK_PORT")
    val port: Int = 9091,
    @ConfigAlias("OPENRUNE_WORLDS_LINK_SO_BACKLOG")
    val soBacklog: Int = 512,
    @ConfigAlias("OPENRUNE_WORLDS_LINK_READ_TIMEOUT_SEC")
    val readTimeoutSeconds: Int = 120,
)

data class SessionConfig(
    @ConfigAlias("OPENRUNE_SESSIONS_TTL_MS")
    val ttlMs: Long = 60_000,
)

data class AnalyticsConfig(
    @ConfigAlias("OPENRUNE_ONLINE_SAMPLE_INTERVAL_SEC")
    val onlineSampleIntervalSeconds: Int = 3600,
)

data class JavConfigSettings(
    @ConfigAlias("OPENRUNE_JAV_CONFIG_REVISION")
    val revision: Int = 238,
    @ConfigAlias("OPENRUNE_JAV_CONFIG_URL_TEMPLATE")
    val remoteUrlTemplate: String = "https://client.blurite.io/jav_local_%d.ws",
    val configProps: Map<String, String> = emptyMap(),
    @ConfigAlias("OPENRUNE_JAV_CONFIG_REFRESH_MINUTES")
    val refreshMinutes: Int = 15,
    @ConfigAlias("OPENRUNE_JAV_CONFIG_HTTP_TIMEOUT_SEC")
    val httpTimeoutSeconds: Int = 20,
)

data class BadWordsConfig(
    @ConfigAlias("OPENRUNE_BAD_WORDS_URL")
    val remoteUrl: String =
        "https://gist.githubusercontent.com/briankung/e085841a7a13fa4945a0cf482950436a/raw/326b4078db98541204e3d192d7cf84f63cd4c87a/bad_words.txt",
    @ConfigAlias("OPENRUNE_BAD_WORDS_REFRESH_MINUTES")
    val refreshMinutes: Int = 360,
)

data class DevWorldConfig(
    /** When true, inserts development realm/world id 255 on startup if missing. */
    @ConfigAlias("OPENRUNE_DEV_WORLD_AUTO_CREATE")
    val autoCreate: Boolean = true,
)

data class DiagnosticsConfig(
    /** Per-phase login timing lines (Central LOGIN detail, world-link auth, game-thread, account DB load). */
    @ConfigAlias("OPENRUNE_LOGIN_TIMING_LOGS")
    val loginTimingLogs: Boolean = false,
    /** PM delivery trace lines (game send → central receive → push → game inbound → deliver). */
    @ConfigAlias("OPENRUNE_SOCIAL_PM_TRACE_LOGS")
    val socialPmTraceLogs: Boolean = false,
)
