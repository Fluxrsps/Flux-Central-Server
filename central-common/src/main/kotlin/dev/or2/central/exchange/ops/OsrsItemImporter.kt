package dev.or2.central.exchange.ops

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Seeds `exchange_items` from the OSRS real-time prices API: buy limits and alch values from the
 * item mapping, base prices from the latest traded highs and lows.
 *
 * Safe to re-run at any time, which is the point: after a database reset this puts every item
 * back. Base price and buy limit are only filled where they are null, so nothing staff tuned by
 * hand is lost; pass `overwrite` to reset them to the live OSRS figures instead.
 *
 * The wiki asks API users to identify themselves, so [userAgent] should name the server and a way
 * to reach its operator.
 */
class OsrsItemImporter(
    private val items: ItemAdminService,
    private val config: Settings = Settings(),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build(),
) {
    private val log = LoggerFactory.getLogger(OsrsItemImporter::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class Settings(
        val mappingUrl: String = "https://prices.runescape.wiki/api/v2/osrs/mapping",
        val latestUrl: String = "https://prices.runescape.wiki/api/v2/osrs/latest",
        val userAgent: String = "Fluxious Trading Post item seed - info@fluxious-rsps.com",
        /** Members-only items are imported too; set false for an f2p-only economy. */
        val includeMembers: Boolean = true,
        val requestTimeoutSeconds: Long = 60,
    )

    @Serializable
    private data class MappingEntry(
        val id: Int = 0,
        val name: String = "",
        val members: Boolean = false,
        val value: Long? = null,
        val limit: Int? = null,
        val highalch: Long? = null,
        val lowalch: Long? = null,
    )

    class Result(val summary: ItemAdminService.ImportSummary, val mapped: Int, val priced: Int, val limited: Int) {
        override fun toString(): String = "$summary (mapped=$mapped priced=$priced limited=$limited)"
    }

    /** Fetches both endpoints and applies them in one transaction. */
    fun import(staffCharacterId: Int, reason: String = "OSRS item seed", overwrite: Boolean = false): Result {
        val mapping = fetchMapping()
        val latest = fetchLatest()
        log.info("OSRS import: {} mapped items, {} with live prices", mapping.size, latest.size)

        var priced = 0
        var limited = 0
        val seeds =
            mapping.mapNotNull { entry ->
                if (entry.id <= 0) return@mapNotNull null
                if (!config.includeMembers && entry.members) return@mapNotNull null
                val basePrice = latest[entry.id] ?: entry.value?.takeIf { it >= 1 }
                if (latest.containsKey(entry.id)) priced++
                if (entry.limit != null) limited++
                ItemAdminService.ItemSeed(
                    objId = entry.id,
                    basePrice = basePrice,
                    buyLimit = entry.limit?.takeIf { it >= 1 },
                    highAlch = entry.highalch?.takeIf { it >= 1 },
                    shopValue = entry.value?.takeIf { it >= 1 },
                )
            }

        val summary = items.importSeeds(seeds, staffCharacterId, reason, overwrite, source = "OSRS_WIKI")
        log.info("OSRS import complete: {}", summary)
        return Result(summary, mapping.size, priced, limited)
    }

    private fun fetchMapping(): List<MappingEntry> {
        val body = get(config.mappingUrl)
        return json.decodeFromString<List<MappingEntry>>(body)
    }

    /**
     * Mid price per item: the average of the latest high and low where both traded, otherwise
     * whichever side has a figure. Items with no recent trade at all are absent, and fall back to
     * the mapping's store value.
     */
    private fun fetchLatest(): Map<Int, Long> {
        val root = json.parseToJsonElement(get(config.latestUrl)).jsonObject
        val data = root["data"]?.jsonObject ?: JsonObject(emptyMap())
        val out = HashMap<Int, Long>(data.size)
        for ((key, value) in data) {
            val id = key.toIntOrNull() ?: continue
            val entry = value as? JsonObject ?: continue
            val high = entry["high"]?.jsonPrimitive?.longOrNull
            val low = entry["low"]?.jsonPrimitive?.longOrNull
            val mid =
                when {
                    high != null && low != null -> (high + low) / 2
                    else -> high ?: low
                }
            if (mid != null && mid >= 1) out[id] = mid
        }
        return out
    }

    private fun get(url: String): String {
        val request =
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
                .header("User-Agent", config.userAgent)
                .header("Accept", "application/json")
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "$url returned ${response.statusCode()}: ${response.body().take(200)}" }
        return response.body()
    }
}
