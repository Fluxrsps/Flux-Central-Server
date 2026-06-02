package dev.or2.central.util.config

/**
 * Canonical configuration keys for OpenRune Central.
 *
 * Resolution order: **environment variables first**, then **`central-config.yaml`** overrides any key
 * that is set in the file. Overrides are printed to the console at startup.
 */
enum class CentralConfigKey(
    val envVar: String,
    val yamlPath: String,
    val envAliases: List<String> = emptyList(),
    val yamlPathAliases: List<String> = emptyList(),
    val required: Boolean = false,
) {
    HTTP_PORT("OPENRUNE_HTTP_PORT", "openrune.http.port"),
    HTTP_TRUST_PROXY("OPENRUNE_HTTP_TRUST_PROXY", "openrune.http.trustProxy"),

    JDBC_URL("OPENRUNE_JDBC_URL", "openrune.jdbc.url"),
    DB_HOST("OPENRUNE_DB_HOST", "openrune.db.host"),
    DB_PORT("OPENRUNE_DB_PORT", "openrune.db.port"),
    DB_NAME("OPENRUNE_DB_NAME", "openrune.db.name"),
    DB_USER("OPENRUNE_DB_USER", "openrune.db.user"),
    DB_PASSWORD("OPENRUNE_DB_PASSWORD", "openrune.db.password"),
    DB_POOL_SIZE("OPENRUNE_DB_POOL_SIZE", "openrune.db.poolSize"),
    /** When `false`, user/password are optional (trust/peer auth or credentials embedded in JDBC URL). */
    DB_REQUIRE_CREDENTIALS("OPENRUNE_DB_REQUIRE_CREDENTIALS", "openrune.db.requireCredentials"),

    SESSION_TTL_MS(
        "OPENRUNE_SESSION_TTL_MS",
        "openrune.sessionsTtlMs",
        yamlPathAliases = listOf("openrune.sessionTtlMs"),
    ),

    WORLDS_LINK_PORT("OPENRUNE_WORLD_LINK_PORT", "openrune.worldsLinkPort"),
    WORLDS_LINK_SO_BACKLOG("OPENRUNE_WORLD_LINK_SO_BACKLOG", "openrune.worldsLinkSoBacklog"),
    WORLDS_LINK_READ_TIMEOUT_SEC(
        "OPENRUNE_WORLD_LINK_READ_TIMEOUT_SEC",
        "openrune.worldsLinkReadTimeoutSeconds",
    ),
    WORLDS_LINK_MAX_CONN_PER_IP(
        "OPENRUNE_WORLD_LINK_MAX_CONN_PER_IP",
        "openrune.worldsLinkMaxConnectionsPerIp",
    ),
    WORLDS_LINK_MAX_CONN_TOTAL(
        "OPENRUNE_WORLD_LINK_MAX_CONN_TOTAL",
        "openrune.worldsLinkMaxConnectionsTotal",
    ),
    WORLDS_LINK_HANDLER_THREADS(
        "OPENRUNE_WORLD_LINK_HANDLER_THREADS",
        "openrune.worldsLinkHandlerThreads",
    ),
    WORLDS_LINK_HANDLER_QUEUE(
        "OPENRUNE_WORLD_LINK_HANDLER_QUEUE",
        "openrune.worldsLinkHandlerQueueSize",
    ),
    WORLDS_LINK_MAX_FRAMES_PER_SEC(
        "OPENRUNE_WORLD_LINK_MAX_FRAMES_PER_SEC",
        "openrune.worldsLinkMaxFramesPerSecond",
    ),
    WORLDS_LINK_MAX_FRAME_BURST(
        "OPENRUNE_WORLD_LINK_MAX_FRAME_BURST",
        "openrune.worldsLinkMaxFrameBurst",
    ),

    ONLINE_SAMPLE_INTERVAL_SEC(
        "OPENRUNE_ONLINE_SAMPLE_INTERVAL_SEC",
        "openrune.onlineSampleIntervalSeconds",
    ),

    BAD_WORDS_URL("OPENRUNE_BAD_WORDS_URL", "openrune.badWordsRemoteUrl"),
    BAD_WORDS_REFRESH_MINUTES("OPENRUNE_BAD_WORDS_REFRESH_MINUTES", "openrune.badWordsRefreshMinutes"),
    BAD_WORDS_HTTP_TIMEOUT_SEC(
        "OPENRUNE_BAD_WORDS_HTTP_TIMEOUT_SEC",
        "openrune.badWordsHttpTimeoutSeconds",
    ),

    JAV_CONFIG_REVISION("OPENRUNE_JAV_CONFIG_REVISION", "openrune.javConfig.revision"),
    JAV_CONFIG_URL_TEMPLATE("OPENRUNE_JAV_CONFIG_URL_TEMPLATE", "openrune.javConfig.remoteUrlTemplate"),
    JAV_CONFIG_REFRESH_MINUTES("OPENRUNE_JAV_CONFIG_REFRESH_MINUTES", "openrune.javConfig.refreshMinutes"),
    JAV_CONFIG_HTTP_TIMEOUT_SEC(
        "OPENRUNE_JAV_CONFIG_HTTP_TIMEOUT_SEC",
        "openrune.javConfig.httpTimeoutSeconds",
    ),
    /** Multiline jav_config.ws lines (`title=…`, `param=25=…`, …). */
    JAV_CONFIG_PROPS_BLOCK("OPENRUNE_JAV_CONFIG_PROPS", "openrune.javConfig.configProps"),

    CLOUDFLARED_STATUS(
        "CLOUDFLARED_STATUS",
        "openrune.cloudflared.status",
        envAliases = listOf("OPENRUNE_CLOUDFLARED_STATUS"),
    ),
    CLOUDFLARED_TOKEN(
        "CLOUDFLARED_TOKEN",
        "openrune.cloudflared.token",
        envAliases = listOf("OPENRUNE_CLOUDFLARED_TOKEN"),
    ),
    ;

    val allEnvNames: List<String> = listOf(envVar) + envAliases

    val allYamlPaths: List<String> = listOf(yamlPath) + yamlPathAliases

    companion object {
        private val byYamlPath: Map<String, CentralConfigKey> =
            entries
                .flatMap { key -> key.allYamlPaths.map { path -> path to key } }
                .toMap()

        private val byEnv: Map<String, CentralConfigKey> =
            entries
                .flatMap { key -> key.allEnvNames.map { name -> name to key } }
                .toMap()

        fun fromYamlPath(path: String): CentralConfigKey? = byYamlPath[path]

        fun fromEnv(name: String): CentralConfigKey? = byEnv[name]

        const val JAV_CONFIG_PROPS_PREFIX = "openrune.javConfig.configProps."
    }
}
