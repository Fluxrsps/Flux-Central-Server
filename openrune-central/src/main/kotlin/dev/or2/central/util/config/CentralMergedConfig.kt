package dev.or2.central.util.config

/**
 * Effective configuration: environment layer merged with YAML overrides.
 */
class CentralMergedConfig internal constructor(
    private val values: Map<CentralConfigKey, String>,
    internal val yamlFlat: Map<String, String>,
) {
    fun raw(key: CentralConfigKey): String? = values[key]?.trim()?.takeIf { it.isNotEmpty() }

    fun requireRaw(key: CentralConfigKey, label: String): String =
        raw(key) ?: throw CentralConfigException(missingMessage(key, label))

    fun optionalString(key: CentralConfigKey): String? = raw(key)

    fun string(key: CentralConfigKey, default: String): String = raw(key) ?: default

    fun int(key: CentralConfigKey, default: Int): Int = raw(key)?.toIntOrNull() ?: default

    fun long(key: CentralConfigKey, default: Long): Long = raw(key)?.toLongOrNull() ?: default

    fun boolean(key: CentralConfigKey, default: Boolean): Boolean =
        raw(key)?.let { parseBoolean(it) } ?: default

    fun optionalBoolean(key: CentralConfigKey): Boolean? = raw(key)?.let { parseBoolean(it) }

    internal fun missingMessage(key: CentralConfigKey, label: String): String =
        "Missing required $label: set ${key.envVar} or ${key.yamlPath} in the environment or central-config.yaml"

    companion object {
        internal fun parseBoolean(raw: String): Boolean? =
            when (raw.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }
    }
}
