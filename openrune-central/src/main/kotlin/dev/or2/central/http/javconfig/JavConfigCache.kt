package dev.or2.central.http.javconfig

import dev.or2.central.util.config.CentralRuntimeConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

class JavConfigCache(
    private val config: CentralRuntimeConfig,
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
        val rev = config.javConfigRevision
        val url = String.format(config.javConfigRemoteUrlTemplate, rev)
        val remote = fetchRemote(url)
        return JavConfigMerger.merge(remote, config.javConfigProps)
    }

    private fun fetchRemote(url: String): String {
        val client =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.javConfigHttpTimeoutSeconds.toLong()))
                .build()
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.javConfigHttpTimeoutSeconds.toLong()))
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            error("jav_config remote HTTP ${response.statusCode()} for $url")
        }
        return response.body()
    }
}
