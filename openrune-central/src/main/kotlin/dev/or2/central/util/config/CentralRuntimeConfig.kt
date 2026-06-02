package dev.or2.central.util.config

import dev.or2.central.http.javconfig.JavConfigMerger
import org.slf4j.LoggerFactory

data class CentralRuntimeConfig(
    val jdbcUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val dbMaximumPoolSize: Int,
    val sessionsTtlMillis: Long,
    val worldsLinkPort: Int?,
    val worldsLinkSoBacklog: Int,
    val worldsLinkReadTimeoutSeconds: Int,
    val worldsLinkMaxConnectionsPerIp: Int,
    val worldsLinkMaxConnectionsTotal: Int,
    val worldsLinkHandlerThreads: Int,
    val worldsLinkHandlerQueueSize: Int,
    val worldsLinkMaxFramesPerSecond: Int,
    val worldsLinkMaxFrameBurst: Int,
    val onlineSampleIntervalSeconds: Int,
    /**
     * Remote newline-separated bad-word list (e.g. gist raw URL). Fetched on schedule and merged
     * with classpath `profanity/bad_words_local.txt`.
     */
    val badWordsRemoteUrl: String,
    val badWordsRefreshMinutes: Int,
    val badWordsHttpTimeoutSeconds: Int,
    /** Remote jav config revision (`jav_local_%d.ws`). */
    val javConfigRevision: Int,
    /** `String.format` pattern with one `%d` for revision, e.g. `https://client.blurite.io/jav_local_%d.ws`. */
    val javConfigRemoteUrlTemplate: String,
    /** Logical jav-config keys to replace in the downloaded file (only listed keys are changed). */
    val javConfigProps: Map<String, String>,
    val javConfigRefreshMinutes: Int,
    val javConfigHttpTimeoutSeconds: Int,
    /**
     * When true, trust `X-Forwarded-*` headers from a reverse proxy or Cloudflare Tunnel
     * (`X-Forwarded-Proto`, host, client IP). Enable only when HTTP is not exposed directly.
     * Auto-enabled when Cloudflare Tunnel is configured (`CLOUDFLARED_STATUS` + token)
     * unless overridden by `openrune.http.trustProxy`.
     */
    val httpTrustProxy: Boolean,
)

private val log = LoggerFactory.getLogger("dev.or2.central.config")

fun loadCentralRuntimeConfig(): CentralRuntimeConfig {
    val cfg = loadMergedCentralConfig()
    val database = resolveRequiredDatabaseConfig(cfg)

    val sessionsTtl = cfg.long(CentralConfigKey.SESSION_TTL_MS, 60_000L).coerceAtLeast(1L)

    val handlerThreadsDefault =
        maxOf(4, minOf(32, Runtime.getRuntime().availableProcessors() * 2))

    return CentralRuntimeConfig(
        jdbcUrl = database.jdbcUrl,
        dbUser = database.user,
        dbPassword = database.password,
        dbMaximumPoolSize = database.poolSize,
        sessionsTtlMillis = sessionsTtl,

        worldsLinkPort = resolveWorldLinkPort(cfg.optionalString(CentralConfigKey.WORLDS_LINK_PORT)),

        worldsLinkSoBacklog = cfg.int(CentralConfigKey.WORLDS_LINK_SO_BACKLOG, 512).coerceIn(64, 16_384),

        worldsLinkReadTimeoutSeconds =
            cfg.int(CentralConfigKey.WORLDS_LINK_READ_TIMEOUT_SEC, 120).coerceIn(5, 3600),

        worldsLinkMaxConnectionsPerIp =
            cfg.int(CentralConfigKey.WORLDS_LINK_MAX_CONN_PER_IP, 32).coerceIn(0, 4096),

        worldsLinkMaxConnectionsTotal =
            cfg.int(CentralConfigKey.WORLDS_LINK_MAX_CONN_TOTAL, 4096).coerceIn(0, 262_144),

        worldsLinkHandlerThreads =
            cfg.int(CentralConfigKey.WORLDS_LINK_HANDLER_THREADS, handlerThreadsDefault).coerceIn(1, 256),

        worldsLinkHandlerQueueSize =
            cfg.int(CentralConfigKey.WORLDS_LINK_HANDLER_QUEUE, 2048).coerceIn(32, 65_536),

        worldsLinkMaxFramesPerSecond =
            cfg.int(CentralConfigKey.WORLDS_LINK_MAX_FRAMES_PER_SEC, 80).coerceIn(0, 10_000),

        worldsLinkMaxFrameBurst =
            cfg.int(CentralConfigKey.WORLDS_LINK_MAX_FRAME_BURST, 120).coerceIn(1, 100_000),

        onlineSampleIntervalSeconds =
            cfg.int(CentralConfigKey.ONLINE_SAMPLE_INTERVAL_SEC, 3600).coerceIn(30, 86_400),

        badWordsRemoteUrl =
            cfg.string(
                CentralConfigKey.BAD_WORDS_URL,
                "https://gist.githubusercontent.com/briankung/e085841a7a13fa4945a0cf482950436a/raw/326b4078db98541204e3d192d7cf84f63cd4c87a/bad_words.txt",
            ).trim(),

        badWordsRefreshMinutes =
            cfg.int(CentralConfigKey.BAD_WORDS_REFRESH_MINUTES, 360).coerceIn(5, 10_080),

        badWordsHttpTimeoutSeconds =
            cfg.int(CentralConfigKey.BAD_WORDS_HTTP_TIMEOUT_SEC, 20).coerceIn(3, 120),

        javConfigRevision = cfg.int(CentralConfigKey.JAV_CONFIG_REVISION, 238).coerceAtLeast(1),

        javConfigRemoteUrlTemplate =
            cfg.string(
                CentralConfigKey.JAV_CONFIG_URL_TEMPLATE,
                "https://client.blurite.io/jav_local_%d.ws",
            ).trim(),

        javConfigProps = parseJavConfigProps(cfg),

        javConfigRefreshMinutes =
            cfg.int(CentralConfigKey.JAV_CONFIG_REFRESH_MINUTES, 15).coerceIn(1, 10_080),

        javConfigHttpTimeoutSeconds =
            cfg.int(CentralConfigKey.JAV_CONFIG_HTTP_TIMEOUT_SEC, 20).coerceIn(3, 120),

        httpTrustProxy = resolveHttpTrustProxy(cfg),
    )
}

private fun resolveHttpTrustProxy(cfg: CentralMergedConfig): Boolean {
    cfg.optionalBoolean(CentralConfigKey.HTTP_TRUST_PROXY)?.let { return it }

    if (!CloudflaredTunnelEnv.isConfigured(cfg)) return false

    log.info(
        "Cloudflare Tunnel enabled (CLOUDFLARED_STATUS); " +
            "enabling HTTP reverse-proxy headers (X-Forwarded-*)",
    )
    return true
}

fun centralRuntimeConfigFromJdbc(
    jdbcUrl: String,
    dbUser: String,
    dbPassword: String,
    dbMaximumPoolSize: Int,
    worldLinkPort: Int,
    worldLinkSoBacklog: Int,
    sessionsTtlMillis: Long = 60_000L,
    worldsLinkReadTimeoutSeconds: Int = 120,
    worldsLinkMaxConnectionsPerIp: Int = 32,
    worldsLinkMaxConnectionsTotal: Int = 4096,
    worldsLinkHandlerThreads: Int =
        maxOf(4, minOf(32, Runtime.getRuntime().availableProcessors() * 2)),
    worldsLinkHandlerQueueSize: Int = 2048,
    worldsLinkMaxFramesPerSecond: Int = 80,
    worldsLinkMaxFrameBurst: Int = 120,
    onlineSampleIntervalSeconds: Int = 3600,
    badWordsRemoteUrl: String =
        "https://gist.githubusercontent.com/briankung/e085841a7a13fa4945a0cf482950436a/raw/326b4078db98541204e3d192d7cf84f63cd4c87a/bad_words.txt",
    badWordsRefreshMinutes: Int = 360,
    badWordsHttpTimeoutSeconds: Int = 20,
    javConfigRevision: Int = 238,
    javConfigRemoteUrlTemplate: String = "https://client.blurite.io/jav_local_%d.ws",
    javConfigProps: Map<String, String> = emptyMap(),
    javConfigRefreshMinutes: Int = 15,
    javConfigHttpTimeoutSeconds: Int = 20,
    httpTrustProxy: Boolean = false,
): CentralRuntimeConfig {
    require(jdbcUrl.isNotBlank()) { "jdbcUrl is required" }
    require(dbUser.isNotBlank()) { "dbUser is required" }
    require(dbPassword.isNotBlank()) { "dbPassword is required" }

    return CentralRuntimeConfig(
        jdbcUrl = jdbcUrl.trim(),
        dbUser = dbUser.trim(),
        dbPassword = dbPassword,
        dbMaximumPoolSize = dbMaximumPoolSize.coerceIn(1, 100),
        sessionsTtlMillis = sessionsTtlMillis.coerceAtLeast(1L),

        worldsLinkPort = worldLinkPort.takeUnless { it <= 0 },
        worldsLinkSoBacklog = worldLinkSoBacklog.coerceIn(64, 16_384),

        worldsLinkReadTimeoutSeconds = worldsLinkReadTimeoutSeconds.coerceIn(5, 3600),
        worldsLinkMaxConnectionsPerIp = worldsLinkMaxConnectionsPerIp.coerceIn(0, 4096),
        worldsLinkMaxConnectionsTotal = worldsLinkMaxConnectionsTotal.coerceIn(0, 262_144),
        worldsLinkHandlerThreads = worldsLinkHandlerThreads.coerceIn(1, 256),
        worldsLinkHandlerQueueSize = worldsLinkHandlerQueueSize.coerceIn(32, 65_536),
        worldsLinkMaxFramesPerSecond = worldsLinkMaxFramesPerSecond.coerceIn(0, 10_000),
        worldsLinkMaxFrameBurst = worldsLinkMaxFrameBurst.coerceIn(1, 100_000),

        onlineSampleIntervalSeconds = onlineSampleIntervalSeconds.coerceIn(30, 86_400),

        badWordsRemoteUrl = badWordsRemoteUrl.trim(),
        badWordsRefreshMinutes = badWordsRefreshMinutes.coerceIn(5, 10_080),
        badWordsHttpTimeoutSeconds = badWordsHttpTimeoutSeconds.coerceIn(3, 120),

        javConfigRevision = javConfigRevision.coerceAtLeast(1),
        javConfigRemoteUrlTemplate = javConfigRemoteUrlTemplate.trim(),
        javConfigProps = javConfigProps,
        javConfigRefreshMinutes = javConfigRefreshMinutes.coerceIn(1, 10_080),
        javConfigHttpTimeoutSeconds = javConfigHttpTimeoutSeconds.coerceIn(3, 120),
        httpTrustProxy = httpTrustProxy,
    )
}

data class ResolvedDatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val poolSize: Int,
)

internal fun resolveRequiredDatabaseConfig(cfg: CentralMergedConfig): ResolvedDatabaseConfig {
    val poolSize = cfg.int(CentralConfigKey.DB_POOL_SIZE, 10).coerceIn(1, 100)
    val jdbcUrl = resolveJdbcUrl(cfg, requirePartsWhenUrlMissing = true)
    val requireCredentials = cfg.boolean(CentralConfigKey.DB_REQUIRE_CREDENTIALS, defaultRequireDbCredentials(cfg))

    val user =
        if (requireCredentials) {
            cfg.requireRaw(CentralConfigKey.DB_USER, "database user")
        } else {
            cfg.optionalString(CentralConfigKey.DB_USER).orEmpty()
        }
    val password =
        if (requireCredentials) {
            cfg.requireRaw(CentralConfigKey.DB_PASSWORD, "database password")
        } else {
            cfg.optionalString(CentralConfigKey.DB_PASSWORD).orEmpty()
        }

    return ResolvedDatabaseConfig(
        jdbcUrl = jdbcUrl,
        user = user,
        password = password,
        poolSize = poolSize,
    )
}

/** Default: require user/password unless JDBC URL is set or DB host is local (trust/peer). */
private fun defaultRequireDbCredentials(cfg: CentralMergedConfig): Boolean {
    if (!cfg.raw(CentralConfigKey.JDBC_URL).isNullOrBlank()) return false
    return !isLocalDatabaseHost(cfg.raw(CentralConfigKey.DB_HOST))
}

private fun isLocalDatabaseHost(host: String?): Boolean {
    when (host?.trim()?.lowercase()) {
        "127.0.0.1", "127.0.1", "localhost", "::1" -> return true
    }
    return false
}

/**
 * Resolves JDBC URL from `OPENRUNE_JDBC_URL` / `openrune.jdbc.url`, or from
 * `openrune.db.host` + `openrune.db.port` + `openrune.db.name`.
 */
internal fun resolveJdbcUrl(
    cfg: CentralMergedConfig,
    requirePartsWhenUrlMissing: Boolean = false,
): String {
    cfg.raw(CentralConfigKey.JDBC_URL)?.let { return it }

    val host = cfg.raw(CentralConfigKey.DB_HOST)
    val port = cfg.int(CentralConfigKey.DB_PORT, 5432)
    val name = cfg.raw(CentralConfigKey.DB_NAME)

    if (requirePartsWhenUrlMissing) {
        val resolvedHost =
            host?.takeIf { it.isNotEmpty() }
                ?: throw CentralConfigException(
                    cfg.missingMessage(CentralConfigKey.DB_HOST, "database host"),
                )
        val resolvedName =
            name?.takeIf { it.isNotEmpty() }
                ?: throw CentralConfigException(
                    cfg.missingMessage(CentralConfigKey.DB_NAME, "database name"),
                )
        return "jdbc:postgresql://$resolvedHost:$port/$resolvedName"
    }

    if (!host.isNullOrBlank() && !name.isNullOrBlank()) {
        return "jdbc:postgresql://${host.trim()}:$port/${name.trim()}"
    }

    throw CentralConfigException(
        "Database is not configured: set ${CentralConfigKey.JDBC_URL.envVar} / ${CentralConfigKey.JDBC_URL.yamlPath}, or " +
            "${CentralConfigKey.DB_HOST.envVar} + ${CentralConfigKey.DB_NAME.envVar} " +
            "(and usually ${CentralConfigKey.DB_USER.envVar} / ${CentralConfigKey.DB_PASSWORD.envVar}) " +
            "in the environment or central-config.yaml",
    )
}

internal fun mergeJavConfigOverrideLines(
    result: MutableMap<String, String>,
    rawBlock: String,
) {
    val normalized = rawBlock.replace("\\n", "\n")
    for (line in normalized.lines()) {
        JavConfigMerger.parseOverrideEntryLine(line)?.let { (key, value) ->
            result[key] = value
        }
    }
}

private fun parseJavConfigProps(cfg: CentralMergedConfig): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val prefix = CentralConfigKey.JAV_CONFIG_PROPS_PREFIX

    for ((path, value) in cfg.yamlFlat) {
        if (!path.startsWith(prefix)) continue
        val suffix = path.removePrefix(prefix)
        if (value.isBlank()) continue
        result[JavConfigMerger.configPropSuffixToLineKey(suffix)] = value.trim()
    }

    cfg.raw(CentralConfigKey.JAV_CONFIG_PROPS_BLOCK)?.let { mergeJavConfigOverrideLines(result, it) }

    return result
}

fun applyKtorHttpPortFromCentralConfigBeforeEngineStart() {
    val cfg = loadMergedCentralConfig()
    val port = cfg.int(CentralConfigKey.HTTP_PORT, -1).takeIf { it in 1..65535 }

    if (port != null) {
        System.setProperty("ktor.deployment.port", port.toString())
        log.info("Ktor HTTP port set to {}", port)
    }
}

private fun resolveWorldLinkPort(raw: String?): Int? {
    val r = raw?.trim().orEmpty()

    return when {
        r.equals("false", true) || r == "0" -> null
        r.isBlank() -> 9091
        else -> r.toIntOrNull() ?: 9091
    }
}
