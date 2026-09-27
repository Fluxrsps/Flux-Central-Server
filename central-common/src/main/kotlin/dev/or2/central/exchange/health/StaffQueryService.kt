package dev.or2.central.exchange.health

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

typealias Row = Map<String, Any?>

class StaffQueryService(private val dataSource: DataSource) {
    fun marketOverview(since: Instant): Row =
        one(
            """
            SELECT
              (SELECT COALESCE(sum(quantity), 0) FROM exchange_trades WHERE executed_at > ? AND source = 'PLAYER') AS player_volume,
              (SELECT COALESCE(sum(gross_value), 0) FROM exchange_trades WHERE executed_at > ? AND source = 'PLAYER') AS player_value,
              (SELECT count(*) FROM exchange_trades WHERE executed_at > ? AND source = 'PLAYER') AS player_trades,
              (SELECT COALESCE(sum(quantity), 0) FROM exchange_trades WHERE executed_at > ? AND source IN ('SYSTEM', 'ADMIN')) AS system_volume,
              (SELECT count(*) FROM exchange_trades WHERE executed_at > ? AND source IN ('SYSTEM', 'ADMIN')) AS system_trades,
              (SELECT count(*) FROM exchange_orders WHERE status IN ('OPEN', 'PARTIALLY_FILLED')) AS active_orders,
              (SELECT count(DISTINCT c) FROM (
                  SELECT buyer_character_id c FROM exchange_trades WHERE executed_at > ? AND source = 'PLAYER'
                  UNION SELECT seller_character_id FROM exchange_trades WHERE executed_at > ? AND source = 'PLAYER') x) AS active_traders,
              (SELECT COALESCE(sum(tax), 0) FROM exchange_trades WHERE executed_at > ?) AS tax_sunk
            """,
            since, since, since, since, since, since, since, since,
        )

    fun priceMovers(since: Instant, limit: Int, rising: Boolean): List<Row> =
        many(
            """
            SELECT p.obj_id, p.price, h.new_price AS price_then,
                   (p.price - h.new_price) * 10000 / h.new_price AS change_bps
            FROM exchange_market_prices p
            JOIN LATERAL (
                SELECT new_price FROM exchange_market_price_history x
                WHERE x.obj_id = p.obj_id AND x.computed_at <= ? ORDER BY x.computed_at DESC LIMIT 1
            ) h ON TRUE
            WHERE h.new_price > 0
            ORDER BY change_bps ${if (rising) "DESC" else "ASC"}
            LIMIT ?
            """,
            since, limit,
        )

    fun topVolume(since: Instant, limit: Int): List<Row> =
        many(
            """
            SELECT obj_id, sum(volume) AS volume, sum(player_volume) AS player_volume, sum(system_volume) AS system_volume,
                   sum(transaction_count) AS trades, sum(tax_collected) AS tax
            FROM exchange_stats_hourly WHERE bucket_start > ?
            GROUP BY obj_id ORDER BY volume DESC LIMIT ?
            """,
            since, limit,
        )

    fun taxByDay(days: Int): List<Row> =
        many(
            """
            SELECT (executed_at AT TIME ZONE 'UTC')::date AS day, sum(tax) AS tax, count(*) AS trades
            FROM exchange_trades WHERE executed_at > ?
            GROUP BY 1 ORDER BY 1
            """,
            Instant.now().minus(Duration.ofDays(days.toLong())),
        )

    fun systemActivity(since: Instant): List<Row> =
        many(
            """
            SELECT obj_id, side, sum(filled) AS filled, sum(gp) AS gp
            FROM exchange_system_usage WHERE character_id = 0 AND bucket_start > ?
            GROUP BY obj_id, side ORDER BY obj_id, side
            """,
            since,
        )

    fun openFlags(limit: Int): List<Row> =
        many(
            "SELECT id, kind, trade_id, order_id, obj_id, character_id, counterparty_id, details::text, created_at " +
                "FROM exchange_flags WHERE status = 'OPEN' ORDER BY created_at DESC LIMIT ?",
            limit,
        )

    fun accountTimeline(characterId: Int, since: Instant, objId: Int? = null): List<Row> =
        many(
            """
            SELECT id, event_type, order_id, trade_id, claim_id, obj_id, quantity, amount, reason, correlation_id, occurred_at
            FROM exchange_events
            WHERE character_id = ? AND occurred_at > ? AND (? IS NULL OR obj_id = ?)
            ORDER BY occurred_at, id
            """,
            characterId, since, objId, objId,
        )

    fun counterparties(characterId: Int, since: Instant, limit: Int): List<Row> =
        many(
            """
            SELECT CASE WHEN buyer_character_id = ? THEN seller_character_id ELSE buyer_character_id END AS counterparty,
                   count(*) AS trades, sum(gross_value) AS value,
                   avg(CASE WHEN p.price > 0 THEN (t.unit_price - p.price) * 10000.0 / p.price END) AS avg_vs_market_bps
            FROM exchange_trades t LEFT JOIN exchange_market_prices p ON p.obj_id = t.obj_id
            WHERE (buyer_character_id = ? OR seller_character_id = ?) AND executed_at > ?
            GROUP BY 1 ORDER BY trades DESC LIMIT ?
            """,
            characterId, characterId, characterId, since, limit,
        )

    fun itemTrades(objId: Int, since: Instant, limit: Int): List<Row> =
        many(
            "SELECT id, buyer_character_id, seller_character_id, quantity, unit_price, tax, source, flags, executed_at " +
                "FROM exchange_trades WHERE obj_id = ? AND executed_at > ? ORDER BY executed_at DESC LIMIT ?",
            objId, since, limit,
        )

    fun orderDetail(orderId: Long): Map<String, Any?> =
        mapOf(
            "order" to one("SELECT * FROM exchange_orders WHERE id = ?", orderId),
            "trades" to many("SELECT * FROM exchange_trades WHERE buy_order_id = ? OR sell_order_id = ? ORDER BY executed_at", orderId, orderId),
            "events" to many("SELECT * FROM exchange_events WHERE order_id = ? ORDER BY occurred_at, id", orderId),
        )

    fun tradeDetail(tradeId: Long): Map<String, Any?> =
        mapOf(
            "trade" to one("SELECT * FROM exchange_trades WHERE id = ?", tradeId),
            "events" to many("SELECT * FROM exchange_events WHERE trade_id = ? ORDER BY occurred_at, id", tradeId),
            "flags" to many("SELECT * FROM exchange_flags WHERE trade_id = ?", tradeId),
        )

    fun followTrade(tradeId: Long, hops: Int = 2): List<Row> {
        val trade = one("SELECT buyer_character_id, seller_character_id, obj_id, executed_at FROM exchange_trades WHERE id = ?", tradeId)
        val buyer = trade["buyer_character_id"] as? Int ?: return emptyList()
        val seller = trade["seller_character_id"] as Int
        val objId = trade["obj_id"] as Int
        val at = (trade["executed_at"] as OffsetDateTime).toInstant()
        return many(
            """
            SELECT id, buyer_character_id, seller_character_id, obj_id, quantity, unit_price, executed_at
            FROM exchange_trades
            WHERE executed_at > ? AND executed_at < ?
              AND (seller_character_id IN (?, ?) OR buyer_character_id IN (?, ?))
            ORDER BY executed_at LIMIT ?
            """,
            at, at.plus(Duration.ofDays(hops.toLong())), buyer, seller, buyer, seller, 200,
        )
    }

    private fun one(sql: String, vararg params: Any?): Row = many(sql, *params).firstOrNull() ?: emptyMap()

    private fun many(sql: String, vararg params: Any?): List<Row> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql.trimIndent()).use { ps ->
                params.forEachIndexed { i, p -> ps.bind(i + 1, p) }
                ps.executeQuery().use { rs -> rs.rows() }
            }
        }

    private fun PreparedStatement.bind(index: Int, value: Any?) {
        when (value) {
            null -> setNull(index, java.sql.Types.INTEGER)
            is Instant -> setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
            is Int -> setInt(index, value)
            is Long -> setLong(index, value)
            is String -> setString(index, value)
            else -> setObject(index, value)
        }
    }

    private fun ResultSet.rows(): List<Row> {
        val meta = metaData
        val out = mutableListOf<Row>()
        while (next()) {
            val row = LinkedHashMap<String, Any?>(meta.columnCount)
            for (i in 1..meta.columnCount) {
                val value = getObject(i)
                row[meta.getColumnLabel(i)] = if (value is java.sql.Array) (value.array as Array<*>).toList() else value
            }
            out += row
        }
        return out
    }
}
