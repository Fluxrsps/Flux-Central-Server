package dev.or2.central.exchange.pricing

import dev.or2.central.exchange.model.OrderSource
import dev.or2.central.exchange.model.Side
import dev.or2.sql.OpenRuneSql
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class MarketPriceRepository {
    class PricedItem(val objId: Int, val basePrice: Long?, val launchState: LaunchState, val price: Long?)

    class CurrentPrice(val price: Long, val stablePrice: Long, val confidence: Double, val updatedAt: Instant)

    private fun sql(name: String) = OpenRuneSql.text("central/exchange/$name.sql")

    fun selectItems(conn: Connection): List<PricedItem> =
        conn.prepareStatement(sql("pricing_select_items")).use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<PricedItem>()
                while (rs.next()) {
                    out +=
                        PricedItem(
                            objId = rs.getInt(1),
                            basePrice = rs.getLong(2).takeUnless { rs.wasNull() },
                            launchState = LaunchState.valueOf(rs.getString(3)),
                            price = rs.getLong(4).takeUnless { rs.wasNull() },
                        )
                }
                out
            }
        }

    fun selectTrades(conn: Connection, objId: Int, since: Instant): List<PriceTrade> =
        conn.prepareStatement(sql("pricing_select_trades")).use { ps ->
            ps.setInt(1, objId)
            ps.setInstant(2, since)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<PriceTrade>()
                while (rs.next()) {
                    val flags = rs.getArray(6)?.let { (it.array as Array<*>).map { f -> f as String } } ?: emptyList()
                    val source = rs.getString(5)
                    val seeded = source == "SEEDED"
                    out +=
                        PriceTrade(
                            buyerCharacterId = rs.getInt(1).takeUnless { rs.wasNull() },
                            sellerCharacterId = rs.getInt(2).takeUnless { rs.wasNull() },
                            quantity = rs.getLong(3),
                            unitPrice = rs.getLong(4),
                            source = if (seeded) OrderSource.PLAYER else OrderSource.valueOf(source),
                            flags = if (seeded) flags + PriceTrade.SEEDED_FLAG else flags,
                            executedAt = rs.getObject(7, OffsetDateTime::class.java).toInstant(),
                        )
                }
                out
            }
        }

    fun selectDepth(conn: Connection, objId: Int, lowPrice: Long, highPrice: Long): List<DepthOrder> =
        conn.prepareStatement(sql("pricing_select_depth")).use { ps ->
            ps.setInt(1, objId)
            ps.setLong(2, lowPrice)
            ps.setLong(3, highPrice)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<DepthOrder>()
                while (rs.next()) {
                    out +=
                        DepthOrder(
                            side = Side.valueOf(rs.getString(1)),
                            limitPrice = rs.getLong(2),
                            remaining = rs.getLong(3),
                            createdAt = rs.getObject(4, OffsetDateTime::class.java).toInstant(),
                        )
                }
                out
            }
        }

    fun selectPrice(conn: Connection, objId: Int): CurrentPrice? =
        conn.prepareStatement(sql("pricing_select_price")).use { ps ->
            ps.setInt(1, objId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                CurrentPrice(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3).toDouble(), rs.getObject(4, OffsetDateTime::class.java).toInstant())
            }
        }

    fun upsertPrice(conn: Connection, objId: Int, price: Long, stablePrice: Long, confidence: Double, now: Instant) {
        conn.prepareStatement(sql("pricing_upsert_price")).use { ps ->
            ps.setInt(1, objId)
            ps.setLong(2, price)
            ps.setLong(3, stablePrice)
            ps.setBigDecimal(4, BigDecimal.valueOf(confidence).setScale(5, java.math.RoundingMode.HALF_UP))
            ps.setInstant(5, now)
            ps.executeUpdate()
        }
    }

    fun insertHistory(conn: Connection, result: MarketPriceResult, now: Instant, staffCharacterId: Int? = null) {
        conn.prepareStatement(sql("pricing_insert_history")).use { ps ->
            ps.setInt(1, result.objId)
            ps.setInstant(2, now)
            ps.setLong(3, result.oldPrice)
            ps.setLong(4, result.newPrice)
            ps.setNullableDecimal(5, result.vwap, 6)
            ps.setBigDecimal(6, BigDecimal.valueOf(result.pressure).setScale(8, java.math.RoundingMode.HALF_UP))
            ps.setBigDecimal(7, BigDecimal.valueOf(result.confidence).setScale(5, java.math.RoundingMode.HALF_UP))
            ps.setBigDecimal(8, BigDecimal.valueOf(result.anchorWeight).setScale(5, java.math.RoundingMode.HALF_UP))
            if (result.anchorPrice == null) ps.setNull(9, Types.BIGINT) else ps.setLong(9, result.anchorPrice)
            ps.setNullableDecimal(10, result.marketTarget, 6)
            ps.setNullableDecimal(11, result.anchoredTarget, 6)
            ps.setBigDecimal(12, BigDecimal.valueOf(result.maxChange).setScale(5, java.math.RoundingMode.HALF_UP))
            ps.setBoolean(13, result.clamped)
            ps.setInt(14, result.tradesUsed)
            ps.setInt(15, result.tradesExcluded)
            ps.setInt(16, result.distinctBuyers)
            ps.setInt(17, result.distinctSellers)
            ps.setLong(18, result.buyDepth)
            ps.setLong(19, result.sellDepth)
            ps.setBigDecimal(20, BigDecimal.valueOf(result.seededWeight).setScale(5, java.math.RoundingMode.HALF_UP))
            ps.setString(21, result.reason)
            if (staffCharacterId == null) ps.setNull(22, Types.INTEGER) else ps.setInt(22, staffCharacterId)
            ps.executeUpdate()
        }
    }

    fun selectRecentPrices(conn: Connection, objId: Int, since: Instant): List<Long> =
        conn.prepareStatement(sql("pricing_select_recent_prices")).use { ps ->
            ps.setInt(1, objId)
            ps.setInstant(2, since)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Long>()
                while (rs.next()) out += rs.getLong(1)
                out
            }
        }

    fun updateLaunchState(conn: Connection, objId: Int, state: LaunchState) {
        conn.prepareStatement(sql("pricing_update_launch_state")).use { ps ->
            ps.setString(1, state.name)
            ps.setInt(2, objId)
            ps.executeUpdate()
        }
    }

    private fun PreparedStatement.setInstant(index: Int, value: Instant) {
        setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
    }

    private fun PreparedStatement.setNullableDecimal(index: Int, value: Double?, scale: Int) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            setNull(index, Types.NUMERIC)
        } else {
            setBigDecimal(index, BigDecimal.valueOf(value).setScale(scale, java.math.RoundingMode.HALF_UP))
        }
    }
}
