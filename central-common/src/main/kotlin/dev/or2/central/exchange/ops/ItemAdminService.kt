package dev.or2.central.exchange.ops

import dev.or2.central.exchange.ExchangeConfig
import dev.or2.central.exchange.ExchangeEngine
import dev.or2.central.exchange.ExchangeRepository
import dev.or2.central.exchange.health.AlertService
import dev.or2.central.exchange.health.AlertSeverity
import dev.or2.central.exchange.model.CancelReason
import dev.or2.central.exchange.pricing.LaunchState
import dev.or2.sql.OpenRuneSql
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.sql.Connection
import java.sql.Types
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

class ItemAdminService(
    private val dataSource: DataSource,
    private val engine: ExchangeEngine?,
    private val config: () -> ExchangeConfig,
    private val alerts: AlertService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val repo = ExchangeRepository()
    private val json = Json { ignoreUnknownKeys = true }

    fun delist(objId: Int, staffCharacterId: Int, reason: String): Int {
        require(reason.isNotBlank())
        val engine = checkNotNull(engine) { "delisting needs an exchange engine" }
        dataSource.connection.use { conn ->
            update(conn, objId, frozen = true)
            repo.insertEvent(conn, event("ITEM_DELISTED", objId, staffCharacterId, reason))
        }
        return engine.cancelAllForItem(objId, CancelReason.DELISTED, staffCharacterId, reason)
    }

    fun relist(objId: Int, staffCharacterId: Int, reason: String) {
        require(reason.isNotBlank())
        dataSource.connection.use { conn ->
            update(conn, objId, frozen = false)
            repo.insertEvent(conn, event("ADMIN_ACTION", objId, staffCharacterId, reason, """{"action":"RELIST"}"""))
        }
    }

    fun setBasePrice(objId: Int, basePrice: Long, staffCharacterId: Int, reason: String) {
        require(basePrice >= 1 && reason.isNotBlank())
        dataSource.connection.use { conn ->
            ensureItem(conn, objId)
            update(conn, objId, basePrice = basePrice)
            repo.insertEvent(conn, event("BASE_PRICE_SET", objId, staffCharacterId, reason, """{"base_price":$basePrice}"""))
        }
    }

    fun setBuyLimit(objId: Int, buyLimit: Int, staffCharacterId: Int, reason: String) {
        require(buyLimit >= 1 && reason.isNotBlank())
        dataSource.connection.use { conn ->
            ensureItem(conn, objId)
            update(conn, objId, buyLimit = buyLimit)
            repo.insertEvent(conn, event("ADMIN_ACTION", objId, staffCharacterId, reason, """{"action":"SET_BUY_LIMIT","buy_limit":$buyLimit}"""))
        }
    }

    fun launch(objId: Int, staffCharacterId: Int, reason: String, launchBuyLimit: Int? = null, hours: Int? = null, basePrice: Long? = null) {
        require(reason.isNotBlank())
        val cfg = config().ops
        val limit = launchBuyLimit ?: cfg.launchDefaultBuyLimit
        val until = clock.instant().plus(Duration.ofHours((hours ?: cfg.launchDefaultHours).toLong()))
        dataSource.connection.use { conn ->
            ensureItem(conn, objId)
            update(conn, objId, basePrice = basePrice, launchBuyLimit = limit, launchLimitUntil = until, launchState = LaunchState.DISCOVERY)
            repo.insertEvent(conn, event("ADMIN_ACTION", objId, staffCharacterId, reason, """{"action":"LAUNCH","launch_buy_limit":$limit,"until":"$until"}"""))
            alerts.raise(conn, "NEW_ITEM_WATCH", objId, AlertSeverity.INFO, """{"launch_buy_limit":$limit,"watch_until":"$until","price_state":"DISCOVERY"}""")
        }
    }

    class ItemSeed(
        val objId: Int,
        val basePrice: Long? = null,
        val buyLimit: Int? = null,
        val highAlch: Long? = null,
        val shopValue: Long? = null,
        val taxExempt: Boolean = false,
    ) {
        val empty: Boolean
            get() = basePrice == null && buyLimit == null && highAlch == null && shopValue == null
    }

    class ImportSummary(val inserted: Int, val updated: Int, val unchanged: Int, val skipped: Int) {
        val total: Int
            get() = inserted + updated + unchanged

        override fun toString(): String = "inserted=$inserted updated=$updated unchanged=$unchanged skipped=$skipped"
    }

    fun itemCount(): Long =
        dataSource.connection.use { conn ->
            conn.prepareStatement(OpenRuneSql.text("central/exchange/item_count.sql")).use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }

    fun importItems(jsonText: String, staffCharacterId: Int, reason: String, overwrite: Boolean = false): ImportSummary {
        val root = json.parseToJsonElement(jsonText).jsonObject
        var malformed = 0
        val seeds =
            root.mapNotNull { (key, value) ->
                val objId = key.toIntOrNull()
                val entry = value as? JsonObject
                if (objId == null || entry == null) {
                    malformed++
                    return@mapNotNull null
                }
                ItemSeed(
                    objId = objId,
                    basePrice = entry["base_price"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 1 },
                    buyLimit = entry["buy_limit"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 1 }?.toInt(),
                    highAlch = entry["high_alch"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 1 },
                    shopValue = entry["shop_value"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 1 },
                )
            }
        val summary = importSeeds(seeds, staffCharacterId, reason, overwrite, source = "JSON")
        return ImportSummary(summary.inserted, summary.updated, summary.unchanged, summary.skipped + malformed)
    }

    fun importSeeds(
        seeds: Collection<ItemSeed>,
        staffCharacterId: Int,
        reason: String,
        overwrite: Boolean = false,
        source: String = "SEEDS",
    ): ImportSummary {
        require(reason.isNotBlank())
        var inserted = 0
        var updated = 0
        var unchanged = 0
        var skipped = 0
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(OpenRuneSql.text("central/exchange/item_upsert_seed.sql")).use { ps ->
                    for (seed in seeds) {
                        if (seed.objId <= 0 || seed.empty) {
                            skipped++
                            continue
                        }
                        val before = repo.findItem(conn, seed.objId)
                        ps.setInt(1, seed.objId)
                        ps.setNullableLong(2, seed.basePrice)
                        ps.setNullableInt(3, seed.buyLimit)
                        ps.setNullableLong(4, seed.highAlch)
                        ps.setNullableLong(5, seed.shopValue)
                        ps.setBoolean(6, seed.taxExempt)
                        ps.setBoolean(7, overwrite)
                        ps.setBoolean(8, overwrite)
                        val (wasInserted, afterPrice, afterLimit) =
                            ps.executeQuery().use { rs ->
                                check(rs.next()) { "seed upsert returned no row for ${seed.objId}" }
                                Triple(rs.getBoolean(1), rs.getLong(2).takeUnless { rs.wasNull() }, rs.getInt(3).takeUnless { rs.wasNull() })
                            }
                        when {
                            wasInserted -> inserted++
                            before != null && (before.basePrice != afterPrice || before.buyLimit != afterLimit) -> {
                                updated++
                                if (overwrite && before.basePrice != null && before.basePrice != afterPrice) {
                                    repo.insertEvent(
                                        conn,
                                        event("BASE_PRICE_SET", seed.objId, staffCharacterId, reason, """{"base_price":$afterPrice,"previous":${before.basePrice},"import":"$source"}"""),
                                    )
                                }
                            }
                            else -> unchanged++
                        }
                    }
                }
                repo.insertEvent(
                    conn,
                    ExchangeRepository.EventRow(
                        type = "ADMIN_ACTION", correlationId = UUID.randomUUID(), staffCharacterId = staffCharacterId, reason = reason,
                        metadata = """{"action":"IMPORT_ITEMS","source":"$source","inserted":$inserted,"updated":$updated,"unchanged":$unchanged,"skipped":$skipped,"overwrite":$overwrite}""",
                    ),
                )
                conn.commit()
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
        return ImportSummary(inserted, updated, unchanged, skipped)
    }

    private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
    }

    private fun java.sql.PreparedStatement.setNullableInt(index: Int, value: Int?) {
        if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
    }

    private fun ensureItem(conn: Connection, objId: Int) {
        conn.prepareStatement("INSERT INTO exchange_items (obj_id) VALUES (?) ON CONFLICT (obj_id) DO NOTHING").use { ps ->
            ps.setInt(1, objId)
            ps.executeUpdate()
        }
    }

    private fun update(
        conn: Connection,
        objId: Int,
        frozen: Boolean? = null,
        basePrice: Long? = null,
        buyLimit: Int? = null,
        launchBuyLimit: Int? = null,
        launchLimitUntil: Instant? = null,
        launchState: LaunchState? = null,
    ) {
        conn.prepareStatement(OpenRuneSql.text("central/exchange/item_update_admin.sql")).use { ps ->
            if (frozen == null) ps.setNull(1, Types.BOOLEAN) else ps.setBoolean(1, frozen)
            if (basePrice == null) ps.setNull(2, Types.BIGINT) else ps.setLong(2, basePrice)
            if (buyLimit == null) ps.setNull(3, Types.INTEGER) else ps.setInt(3, buyLimit)
            if (launchBuyLimit == null) ps.setNull(4, Types.INTEGER) else ps.setInt(4, launchBuyLimit)
            if (launchLimitUntil == null) ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE) else ps.setObject(5, OffsetDateTime.ofInstant(launchLimitUntil, ZoneOffset.UTC))
            if (launchState == null) ps.setNull(6, Types.VARCHAR) else ps.setString(6, launchState.name)
            ps.setInt(7, objId)
            check(ps.executeUpdate() == 1) { "item $objId does not exist" }
        }
    }

    private fun event(type: String, objId: Int, staff: Int, reason: String, metadata: String = "{}") =
        ExchangeRepository.EventRow(type = type, objId = objId, correlationId = UUID.randomUUID(), staffCharacterId = staff, reason = reason, metadata = metadata)
}
