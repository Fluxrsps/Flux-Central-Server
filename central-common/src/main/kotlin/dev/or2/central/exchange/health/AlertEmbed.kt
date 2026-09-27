package dev.or2.central.exchange.health

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * How an alert reads when shown to staff, with nothing about how it is delivered.
 *
 * Kept apart from the sender so the wording and colours can be tested without a Discord
 * connection, and so a second destination would not have to restate any of it.
 */
data class AlertEmbed(
    val title: String,
    val description: String,
    val color: Int,
    val thumbnailUrl: String?,
    val fields: List<Field>,
    val footer: String,
    val timestamp: Instant,
) {
    data class Field(val name: String, val value: String, val inline: Boolean = true)
}

class AlertEmbedFactory(private val catalog: ExchangeItemCatalog = ExchangeItemCatalog()) {
    private val json = Json { ignoreUnknownKeys = true }

    fun build(alert: ExchangeAlert): AlertEmbed {
        val (icon, color) =
            when (alert.severity) {
                AlertSeverity.INFO -> "ℹ️" to 3447003
                AlertSeverity.WARN -> "⚠️" to 16753920
                AlertSeverity.CRITICAL -> "🚨" to 15158332
            }

        val item = if (alert.objId == 0) null else catalog.find(alert.objId)

        val description =
            when {
                alert.objId == 0 -> "Exchange-wide (no single item)"
                item != null && item.gameval.isNotBlank() -> "**${item.name}** (`${item.gameval}` · ${alert.objId})"
                item != null -> "**${item.name}** (${alert.objId})"
                else -> "Item ${alert.objId}"
            }

        val frozen =
            if (alert.kind == "RECONCILIATION" && alert.objId != 0) {
                "\nItem frozen automatically until staff lift it."
            } else {
                ""
            }

        return AlertEmbed(
            title = "$icon ${alert.severity} · ${alert.kind}",
            description = description + frozen,
            color = color,
            thumbnailUrl = if (alert.objId == 0) null else ExchangeEndpoints.iconFor(alert.objId),
            fields = fields(alert.details),
            footer = "alert #${alert.id} · exchange_alerts",
            timestamp = alert.createdAt,
        )
    }

    /** Details are a JSON blob; anything that will not parse is shown as it was written. */
    private fun fields(raw: String): List<AlertEmbed.Field> {
        val details: JsonObject? = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()

        if (details == null || details.isEmpty()) {
            return listOf(AlertEmbed.Field("details", raw.take(1000), inline = false))
        }

        return details.entries.take(25).map { (key, value) ->
            val primitive = value as? JsonPrimitive
            val shown = primitive?.longOrNull?.let { "%,d".format(it) } ?: primitive?.content ?: value.toString()

            AlertEmbed.Field(key.replace('_', ' '), shown.take(1000))
        }
    }
}
