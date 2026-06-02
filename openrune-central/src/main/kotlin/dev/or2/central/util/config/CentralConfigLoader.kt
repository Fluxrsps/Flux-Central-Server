package dev.or2.central.util.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml

private val log = LoggerFactory.getLogger("dev.or2.central.config")

private const val YAML_FILE_NAME = "central-config.yaml"

fun loadMergedCentralConfig(): CentralMergedConfig {
    val envLayer = loadEnvLayer()
    val (yamlLayer, yamlPath, yamlFlat) = loadYamlOverrideLayer()

    if (yamlPath != null) {
        log.info("Loaded central config YAML from {}", yamlPath.toAbsolutePath().normalize())
    } else if (yamlLayer.isEmpty()) {
        log.warn(
            "No {} found; settings come from environment variables only (see CentralConfigKey)",
            YAML_FILE_NAME,
        )
    }

    val (merged, overrides) = mergeConfigLayers(envLayer, yamlLayer)
    reportYamlOverrides(overrides)

    return CentralMergedConfig(merged, yamlFlat)
}

internal fun mergeConfigLayers(
    envLayer: Map<CentralConfigKey, String>,
    yamlLayer: Map<CentralConfigKey, String>,
): Pair<Map<CentralConfigKey, String>, List<ConfigOverride>> {
    val merged = mutableMapOf<CentralConfigKey, String>()
    val overrides = mutableListOf<ConfigOverride>()

    for (key in CentralConfigKey.entries) {
        val envValue = envLayer[key]
        val yamlValue = yamlLayer[key]

        when {
            !yamlValue.isNullOrBlank() -> {
                if (!envValue.isNullOrBlank() && yamlValue.trim() != envValue.trim()) {
                    overrides += ConfigOverride(key, envValue.trim(), yamlValue.trim())
                }
                merged[key] = yamlValue.trim()
            }
            !envValue.isNullOrBlank() -> merged[key] = envValue.trim()
        }
    }

    return merged to overrides
}

internal data class ConfigOverride(
    val key: CentralConfigKey,
    val envValue: String,
    val yamlValue: String,
)

private fun loadEnvLayer(): Map<CentralConfigKey, String> {
    val result = linkedMapOf<CentralConfigKey, String>()
    for (key in CentralConfigKey.entries) {
        envNames@ for (name in key.allEnvNames) {
            val value = readEnv(name) ?: continue@envNames
            result[key] = value
            break@envNames
        }
    }
    return result
}

private fun loadYamlOverrideLayer(): Triple<Map<CentralConfigKey, String>, Path?, Map<String, String>> {
    resolveConfigFilePath()?.let { path ->
        val flat = loadYamlFlat(path)
        return Triple(mapFlatToKeys(flat), path, flat)
    }
    return Triple(emptyMap(), null, emptyMap())
}

private fun mapFlatToKeys(flat: Map<String, String>): Map<CentralConfigKey, String> {
    val result = linkedMapOf<CentralConfigKey, String>()
    for ((path, value) in flat) {
        if (value.isBlank()) continue
        CentralConfigKey.fromYamlPath(path)?.let { result[it] = value.trim() }
    }
    return result
}

internal fun jarSiblingDirectory(): java.nio.file.Path? {
    val url = CentralRuntimeConfig::class.java.protectionDomain?.codeSource?.location ?: return null
    return runCatching {
        val p = java.nio.file.Paths.get(url.toURI())
        if (java.nio.file.Files.isRegularFile(p) && p.toString().endsWith(".jar")) p.parent else null
    }.getOrNull()
}

/** Builds merged config from flat yaml paths (tests). */
internal fun mergedConfigFromFlatForTest(flat: Map<String, String>): CentralMergedConfig {
    val keys = mapFlatToKeys(flat)
    return CentralMergedConfig(keys, flat)
}

private fun resolveConfigFilePath(): Path? {
    val userDir = System.getProperty("user.dir") ?: "."

    System.getenv("OPENRUNE_CONFIG")?.trim()?.takeIf { it.isNotEmpty() }?.let { explicit ->
        val path = Paths.get(explicit)
        if (Files.isRegularFile(path)) return path
        log.warn("OPENRUNE_CONFIG points to missing file: {}", explicit)
    }

    val cwdYaml = Paths.get(userDir, YAML_FILE_NAME)
    if (Files.isRegularFile(cwdYaml)) return cwdYaml

    jarSiblingDirectory()?.resolve(YAML_FILE_NAME)?.takeIf { Files.isRegularFile(it) }?.let { return it }

    return null
}

private fun loadYamlFlat(path: Path): Map<String, String> {
    val raw =
        Files.newInputStream(path).use { ins ->
            Yaml().load<Any?>(ins) ?: emptyMap<String, Any?>()
        }
    return flattenYamlNode(raw)
}

@Suppress("UNCHECKED_CAST")
internal fun flattenYamlNode(node: Any?, prefix: String = ""): Map<String, String> {
    when (node) {
        null -> return emptyMap()
        is Map<*, *> -> {
            val result = linkedMapOf<String, String>()
            for ((k, v) in node) {
                val segment = k?.toString() ?: continue
                val path = if (prefix.isEmpty()) segment else "$prefix.$segment"
                result.putAll(flattenYamlNode(v, path))
            }
            return result
        }
        is Iterable<*> -> return emptyMap()
        else -> {
            if (prefix.isEmpty()) return emptyMap()
            return mapOf(prefix to node.toString().trim())
        }
    }
}

private fun reportYamlOverrides(overrides: List<ConfigOverride>) {
    if (overrides.isEmpty()) return

    println("[Config] central-config.yaml overrides ${overrides.size} environment value(s):")
    for (o in overrides) {
        println("[Config]  - ${o.key.envVar}")
    }
    log.info(
        "Config YAML overrides ({}): {}",
        overrides.size,
        overrides.joinToString(", ") { it.key.envVar },
    )
}

private fun readEnv(name: String): String? =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
