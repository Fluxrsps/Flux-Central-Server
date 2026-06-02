package dev.or2.central.account

import dev.or2.central.util.config.CentralRuntimeConfig
import dev.or2.sql.OpenRuneSql
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

/**
 * Merges `profanity/bad_words_local.txt`, `profanity/bad_words_custom.txt`, and the optional remote gist,
 * for world-link validation and admin bad-word listing.
 */
class BadWordIndex(
    private val config: CentralRuntimeConfig,
) {
    private val log = LoggerFactory.getLogger(BadWordIndex::class.java)
    private val merged =
        AtomicReference(
            run {
                try {
                    loadMergedStatic(config)
                } catch (e: Exception) {
                    log.warn("Bad words initial merge failed (using classpath only): {}", e.message)
                    loadClasspathOnlyStatic()
                }
            },
        )

    fun roots(): Set<String> = merged.get()

    fun mergedLinesText(): String = roots().sorted().joinToString("\n")

    fun refresh() {
        merged.set(
            try {
                loadMergedStatic(config)
            } catch (e: Exception) {
                log.warn("Bad words refresh merge failed: {}", e.message)
                loadClasspathOnlyStatic()
            },
        )
    }

    private companion object {
        fun loadClasspathOnlyStatic(): Set<String> {
            val local =
                readUtf8("profanity/bad_words_local.txt")
            val custom =
                readUtf8("profanity/bad_words_custom.txt")
            return AccountNameAuthPolicy.parseBadWordLines(local + "\n" + custom)
        }

        fun loadMergedStatic(config: CentralRuntimeConfig): Set<String> {
            val local =
                readUtf8("profanity/bad_words_local.txt")
            val custom =
                readUtf8("profanity/bad_words_custom.txt")
            val remote = fetchRemoteWordsStatic(config)
            return AccountNameAuthPolicy.parseBadWordLines(local + "\n" + custom + "\n" + remote)
        }

        private fun readUtf8(resourcePath: String): String =
            OpenRuneSql::class.java.classLoader.getResourceAsStream(resourcePath)
                ?.use { ins ->
                    InputStreamReader(ins, StandardCharsets.UTF_8).use { it.readText() }
                }.orEmpty()

        private val logStatic = LoggerFactory.getLogger(BadWordIndex::class.java)

        fun fetchRemoteWordsStatic(config: CentralRuntimeConfig): String {
            val url = config.badWordsRemoteUrl
            if (url.isBlank()) {
                return ""
            }
            return try {
                val client =
                    HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(config.badWordsHttpTimeoutSeconds.toLong()))
                        .build()
                val request =
                    HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(config.badWordsHttpTimeoutSeconds.toLong()))
                        .GET()
                        .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                if (response.statusCode() in 200..299) {
                    response.body()
                } else {
                    logStatic.warn("Bad words URL HTTP {}: {}", response.statusCode(), url)
                    ""
                }
            } catch (e: Exception) {
                logStatic.warn("Bad words fetch failed: {}", e.message)
                ""
            }
        }
    }
}
