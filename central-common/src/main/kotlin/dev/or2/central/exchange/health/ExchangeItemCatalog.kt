package dev.or2.central.exchange.health

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** Where the exchange reads item facts and icons from. Fluxious' own CDN, so not configurable. */
object ExchangeEndpoints {
    const val CDN = "https://cdn.fluxious-rsps.com"

    const val ITEMS = "$CDN/cache-data/items.json"

    const val ITEM_ICON = "$CDN/items-icons/512/{id}.png"

    fun iconFor(objId: Int): String = ITEM_ICON.replace("{id}", objId.toString())
}

/**
 * Item names for anything the exchange shows to a person.
 *
 * Reads the cache-data document rather than the gamevals one: gamevals only map a symbol to an id,
 * so an alert built from them said "Deathrune" where a player reads "Death rune". This carries the
 * cache's own display name alongside the symbol.
 *
 * Fetched lazily and kept for a day. A failure leaves whatever was already loaded in place and is
 * logged once per attempt; a missing name only costs an alert its label.
 */
class ExchangeItemCatalog(
    private val url: String = ExchangeEndpoints.ITEMS,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val ttl: Duration = Duration.ofDays(1),
) {
    private val log = LoggerFactory.getLogger(ExchangeItemCatalog::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class Item(val id: Int, val name: String, val gameval: String)

    @Volatile
    private var items: Map<Int, Item> = emptyMap()

    @Volatile
    private var loadedAt: Instant = Instant.EPOCH

    fun find(objId: Int): Item? = load()[objId]

    fun nameOf(objId: Int): String? = find(objId)?.name

    private fun load(): Map<Int, Item> {
        val now = Instant.now()

        if (items.isNotEmpty() && Duration.between(loadedAt, now) < ttl) {
            return items
        }

        synchronized(this) {
            if (items.isNotEmpty() && Duration.between(loadedAt, now) < ttl) {
                return items
            }

            runCatching {
                val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build()
                val body = client.send(request, HttpResponse.BodyHandlers.ofString()).body()

                items =
                    json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
                        val row = element.jsonObject
                        val id = row["id"]?.jsonPrimitive?.int ?: return@mapNotNull null
                        val name = row["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        id to Item(id, name, row["gameval"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }.toMap()

                loadedAt = now
            }.onFailure { log.warn("could not load item names from {}", url, it) }
        }

        return items
    }
}
