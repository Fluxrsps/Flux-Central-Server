package dev.or2.central.http

import dev.or2.central.config.CentralConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

class JavConfigCache(
    private val config: CentralConfig,
) {
    private val log = LoggerFactory.getLogger(JavConfigCache::class.java)
    private val payload = AtomicReference<String?>(null)

    fun snapshot(): String? = payload.get()

    fun refresh() {
        try {
            payload.set(fetchAndMerge())
        } catch (e: Exception) {
            log.warn("jav_config refresh failed: {}", e.message)
        }
    }

    private fun fetchAndMerge(): String {
        val rev = config.javConfig.revision
        val url = String.format(config.javConfig.remoteUrlTemplate, rev)
        val remote = fetchRemote(url)
        return mergeProps(remote, config.javConfig.configProps)
    }

    private fun fetchRemote(url: String): String {
        val timeout = config.javConfig.httpTimeoutSeconds.toLong().coerceAtLeast(3)
        val client =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .build()
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            error("jav_config remote HTTP ${response.statusCode()} for $url")
        }
        return response.body()
    }

    internal companion object {
        /**
         * jav_config indexes these families by a second segment (`param=17=<url>`, `msg=ok=OK`),
         * so the identity of such a line is the first two segments, not just the first one.
         */
        private val INDEXED_PREFIXES = setOf("param", "msg")

        internal fun mergeProps(remote: String, overrides: Map<String, String>): String {
            if (overrides.isEmpty()) return remote
            val lines = remote.lines().toMutableList()
            val indexByKey = linkedMapOf<String, Int>()
            lines.forEachIndexed { i, line ->
                val eq = line.indexOf('=')
                if (eq > 0) {
                    indexByKey[keyOf(line.substring(0, eq).trim(), line.substring(eq + 1))] = i
                }
            }
            for ((rawKey, rawValue) in overrides) {
                val head = rawKey.trim()
                val key = keyOf(head, rawValue)
                val value = if (key == head) rawValue else rawValue.substringAfter('=')
                val formatted = "$key=$value"
                val existing = indexByKey[key]
                if (existing != null) {
                    lines[existing] = formatted
                } else {
                    indexByKey[key] = lines.size
                    lines.add(formatted)
                }
            }
            return lines.joinToString("\n")
        }

        /**
         * Resolves the identity of a jav_config entry. `param` + `17=<url>` and `param=17` + `<url>`
         * both resolve to `param=17`, so an override replaces the matching remote line instead of
         * appending a duplicate.
         */
        private fun keyOf(head: String, rest: String): String {
            if (head !in INDEXED_PREFIXES) return head
            val index = rest.substringBefore('=', missingDelimiterValue = "")
            if (index.isEmpty()) return head
            return "$head=$index"
        }
    }
}
