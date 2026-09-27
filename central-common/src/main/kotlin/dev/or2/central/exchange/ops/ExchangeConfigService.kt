package dev.or2.central.exchange.ops

import dev.or2.central.exchange.ExchangeConfig
import dev.or2.central.exchange.ExchangeConfigLoader
import dev.or2.central.exchange.ExchangeConfigProvider
import dev.or2.central.exchange.ExchangeRepository
import dev.or2.sql.OpenRuneSql
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.sql.Types
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * Validated writes to `exchange_config`, Part L.
 *
 * A bad economic parameter is not a cosmetic problem - a negative tax rate or a hundred percent
 * max change would misprice the whole market - so nothing is stored until the resulting
 * configuration has been built and validated in full. Every change keeps its previous value, who
 * made it and why.
 */
class ExchangeConfigService(
    private val dataSource: DataSource,
    private val provider: ExchangeConfigProvider? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val repo = ExchangeRepository()
    private val json = Json { ignoreUnknownKeys = true }
    private val defaultsJson = Json { encodeDefaults = true }

    /** Every key that may be set, with the value currently in force and the shipped default. */
    fun describe(): Map<String, Setting> {
        val defaults = defaultsJson.encodeToJsonElement(ExchangeConfig.DEFAULT).jsonObject
        val stored = loadRaw()
        val effective = defaultsJson.encodeToJsonElement(current()).jsonObject
        return defaults.keys.associateWith { key ->
            Setting(
                key = key,
                default = defaults.getValue(key).toString(),
                stored = stored[key]?.toString(),
                effective = effective[key]?.toString() ?: defaults.getValue(key).toString(),
            )
        }
    }

    class Setting(val key: String, val default: String, val stored: String?, val effective: String) {
        val overridden: Boolean
            get() = stored != null
    }

    fun current(): ExchangeConfig = provider?.invoke() ?: ExchangeConfigLoader.load(dataSource)

    /**
     * Sets one key. [rawValue] is JSON: `200`, `true`, `[1,6,24]`, `{"HIGH_VALUE":100}`, or a
     * nested object for the grouped sections. Throws if the key is unknown or the result fails
     * validation, and nothing is written in that case.
     */
    fun set(key: String, rawValue: String, staffCharacterId: Int?, reason: String) {
        require(reason.isNotBlank()) { "a config change needs a reason" }
        val defaults = defaultsJson.encodeToJsonElement(ExchangeConfig.DEFAULT).jsonObject
        require(key in defaults) { "unknown configuration key '$key'" }

        val parsed =
            runCatching { json.parseToJsonElement(rawValue) }
                .getOrElse { throw IllegalArgumentException("'$rawValue' is not valid JSON for '$key'") }

        // Build the whole configuration with this key replaced and validate it before storing, so
        // a value that would break the engine is refused here rather than at the next reload.
        val proposed = loadRaw().toMutableMap().apply { this[key] = parsed }
        runCatching { ExchangeConfigLoader.merge(proposed) }
            .getOrElse { throw IllegalArgumentException("'$key' = $rawValue is not valid: ${it.message}") }

        val previous = loadRaw()[key]
        write(key, parsed, previous, staffCharacterId, reason)
    }

    /** Removes an override so the shipped default applies again. */
    fun reset(key: String, staffCharacterId: Int?, reason: String) {
        require(reason.isNotBlank()) { "a config change needs a reason" }
        val previous = loadRaw()[key] ?: return
        val now = clock.instant()
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/exchange/config_delete.sql")).use { ps ->
                ps.setString(1, key)
                ps.executeUpdate()
            }
            history(conn, key, previous.toString(), null, staffCharacterId, reason, now)
            repo.insertEvent(conn, event(key, "reset to default", staffCharacterId, reason))
        }
        provider?.invalidate()
    }

    private fun write(key: String, value: JsonElement, previous: JsonElement?, staffCharacterId: Int?, reason: String) {
        val now = clock.instant()
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/exchange/config_upsert.sql")).use { ps ->
                ps.setString(1, key)
                ps.setString(2, value.toString())
                if (staffCharacterId == null) ps.setNull(3, Types.INTEGER) else ps.setInt(3, staffCharacterId)
                ps.setString(4, reason)
                ps.setObject(5, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                ps.executeUpdate()
            }
            history(conn, key, previous?.toString(), value.toString(), staffCharacterId, reason, now)
            repo.insertEvent(conn, event(key, value.toString(), staffCharacterId, reason))
        }
        provider?.invalidate()
    }

    private fun history(
        conn: java.sql.Connection,
        key: String,
        oldValue: String?,
        newValue: String?,
        staffCharacterId: Int?,
        reason: String,
        now: java.time.Instant,
    ) {
        conn.prepareStatement(OpenRuneSql.text("central/exchange/config_history_insert.sql")).use { ps ->
            ps.setString(1, key)
            if (oldValue == null) ps.setNull(2, Types.OTHER) else ps.setString(2, oldValue)
            ps.setString(3, newValue ?: "null")
            if (staffCharacterId == null) ps.setNull(4, Types.INTEGER) else ps.setInt(4, staffCharacterId)
            ps.setString(5, reason)
            ps.setObject(6, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            ps.executeUpdate()
        }
    }

    private fun event(key: String, value: String, staffCharacterId: Int?, reason: String) =
        ExchangeRepository.EventRow(
            type = "CONFIG_CHANGED",
            correlationId = UUID.randomUUID(),
            staffCharacterId = staffCharacterId,
            reason = reason,
            metadata = JsonObject(mapOf("key" to JsonPrimitive(key), "value" to JsonPrimitive(value))).toString(),
        )

    private fun loadRaw(): Map<String, JsonElement> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/exchange/config_load.sql")).use { ps ->
                ps.executeQuery().use { rs ->
                    val out = LinkedHashMap<String, JsonElement>()
                    while (rs.next()) out[rs.getString(1)] = json.parseToJsonElement(rs.getString(2))
                    out
                }
            }
        }
}
