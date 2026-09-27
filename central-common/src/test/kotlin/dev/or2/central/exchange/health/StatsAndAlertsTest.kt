package dev.or2.central.exchange.health

import dev.or2.central.db.FlywayMigrator
import dev.or2.central.exchange.ExchangeConfig
import dev.or2.central.exchange.ExchangeEngine
import dev.or2.central.exchange.HealthConfig
import dev.or2.central.exchange.model.CreateOrderRequest
import dev.or2.central.exchange.model.CreateOrderResult
import dev.or2.central.exchange.model.Side
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Part M's statistics and alert cases: aggregates match raw trades, alerts respect their gates. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatsAndAlertsTest {
    private lateinit var postgres: EmbeddedPostgres
    private lateinit var dataSource: DataSource
    private lateinit var engine: ExchangeEngine
    private lateinit var alerts: AlertService
    private val names = AtomicInteger()
    private val nextItem = AtomicInteger(40_000)
    // Starts at the real clock so the fake one lines up with the timestamps PostgreSQL writes by
    // default; tests move it by whole hours to land trades in the buckets they want.
    private var now: Instant = Instant.now()
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    @BeforeAll
    fun start() {
        postgres = EmbeddedPostgres.builder().start()
        dataSource = postgres.postgresDatabase
        FlywayMigrator.migrate(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "")
        engine = ExchangeEngine(dataSource, { ExchangeConfig() }, clock = clock)
        alerts = AlertService(dataSource, clock = clock)
    }

    @AfterAll
    fun stop() {
        postgres.close()
    }

    @Test
    fun hourlyAggregatesMatchTheRawTrades() {
        val item = newItem()
        trade(item, quantity = 10, price = 100)
        trade(item, quantity = 5, price = 140)
        now = now.plusSeconds(3600)
        trade(item, quantity = 20, price = 90)

        val buckets = scalar("SELECT count(*) FROM exchange_stats_hourly WHERE obj_id = $item")
        assertEquals(2, buckets, "trades in two different hours belong in two buckets")

        assertEquals(
            scalar("SELECT sum(quantity) FROM exchange_trades WHERE obj_id = $item"),
            scalar("SELECT sum(volume) FROM exchange_stats_hourly WHERE obj_id = $item"),
        )
        assertEquals(
            scalar("SELECT count(*) FROM exchange_trades WHERE obj_id = $item"),
            scalar("SELECT sum(transaction_count) FROM exchange_stats_hourly WHERE obj_id = $item"),
        )
        assertEquals(
            scalar("SELECT sum(gross_value) FROM exchange_trades WHERE obj_id = $item"),
            scalar("SELECT sum(vwap_numerator)::bigint FROM exchange_stats_hourly WHERE obj_id = $item"),
        )
        assertEquals(
            scalar("SELECT sum(tax) FROM exchange_trades WHERE obj_id = $item"),
            scalar("SELECT sum(tax_collected) FROM exchange_stats_hourly WHERE obj_id = $item"),
        )
        assertEquals(140, scalar("SELECT max(high_price) FROM exchange_stats_hourly WHERE obj_id = $item"))
        assertEquals(90, scalar("SELECT min(low_price) FROM exchange_stats_hourly WHERE obj_id = $item"))

        StatsRollupService(dataSource, clock).run()
        assertEquals(
            scalar("SELECT sum(volume) FROM exchange_stats_hourly WHERE obj_id = $item"),
            scalar("SELECT sum(volume) FROM exchange_stats_daily WHERE obj_id = $item"),
        )
    }

    @Test
    fun priceAlertsStayQuietBelowTheVolumeAndTraderGates() {
        val quiet = newItem()
        priceMovedOvernight(quiet, from = 100, to = 500)
        trade(quiet, quantity = 1, price = 500)

        val service = MarketAlertService(dataSource, { ExchangeConfig() }, alerts, clock)
        assertEquals(0, service.run(), "one trade on one item must never raise an alert")
        assertEquals(0, openAlerts(quiet))

        val busy = newItem()
        priceMovedOvernight(busy, from = 100, to = 500)
        repeat(6) { trade(busy, quantity = 20, price = 500) }
        assertTrue(service.run() > 0)
        assertEquals(1, openAlerts(busy), "the busy item clears both gates")
        assertEquals("PRICE_CHANGE_24H", string("SELECT kind FROM exchange_alerts WHERE obj_id = $busy AND status = 'OPEN'"))
        assertEquals(0, service.run(), "a second run must not raise the same alert twice")
    }

    @Test
    fun volumeAndTraderSpikesRaiseTheirOwnAlerts() {
        val item = newItem()
        exec("INSERT INTO exchange_market_prices (obj_id, price, stable_price, confidence) VALUES ($item, 100, 100, 0.5)")
        // Yesterday: a trickle from two traders.
        now = now.minusSeconds(36 * 3600)
        repeat(2) { trade(item, quantity = 60, price = 100) }
        // Today: ten times the volume from a crowd.
        now = now.plusSeconds(36 * 3600)
        repeat(8) { trade(item, quantity = 200, price = 100) }

        val service = MarketAlertService(dataSource, { ExchangeConfig(health = HealthConfig(alertMinDistinctTraders = 4)) }, alerts, clock)
        assertTrue(service.run() >= 2)
        val kinds = strings("SELECT kind FROM exchange_alerts WHERE obj_id = $item AND status = 'OPEN' ORDER BY kind")
        assertTrue(kinds.contains("VOLUME_CHANGE"), "kinds were $kinds")
        assertTrue(kinds.contains("TRADERS_CHANGE"), "kinds were $kinds")
    }

    /** Puts a price history row a day back so the alert service has something to compare against. */
    private fun priceMovedOvernight(item: Int, from: Long, to: Long) {
        exec("INSERT INTO exchange_market_prices (obj_id, price, stable_price, confidence) VALUES ($item, $to, $to, 0.5)")
        val yesterday = now.minusSeconds(25 * 3600)
        exec(
            "INSERT INTO exchange_market_price_history (obj_id, computed_at, old_price, new_price, confidence, anchor_weight, max_change) " +
                "VALUES ($item, '$yesterday', $from, $from, 0.5, 0.1, 0.05)",
        )
    }

    /** One fill between two fresh traders, so every trade adds a distinct buyer and seller. */
    private fun trade(item: Int, quantity: Long, price: Long) {
        val seller = character()
        val buyer = character()
        place(seller, item, Side.SELL, quantity, price)
        place(buyer, item, Side.BUY, quantity, price)
    }

    private fun place(character: Int, item: Int, side: Side, quantity: Long, limit: Long) {
        val created = engine.createOrder(CreateOrderRequest(character, item, side, quantity, limit, UUID.randomUUID().toString()))
        assertIs<CreateOrderResult.Pending>(created)
        engine.openOrder(created.order.id, character)
        engine.matchPass(created.order.id)
    }

    private fun openAlerts(item: Int): Long = scalar("SELECT count(*) FROM exchange_alerts WHERE obj_id = $item AND status = 'OPEN'")

    private fun newItem(): Int {
        val id = nextItem.incrementAndGet()
        exec("INSERT INTO exchange_items (obj_id, base_price) VALUES ($id, 100)")
        return id
    }

    private fun character(): Int {
        val name = "stats_${names.incrementAndGet()}"
        exec("INSERT INTO accounts (account_name, password_hash) VALUES ('$name', 'x')")
        exec("INSERT INTO account_characters (account_id, display_name) SELECT id, '$name' FROM accounts WHERE account_name = '$name'")
        return scalar("SELECT id FROM account_characters WHERE display_name = '$name'").toInt()
    }

    private fun exec(sql: String) {
        dataSource.connection.use { it.createStatement().use { st -> st.executeUpdate(sql) } }
    }

    private fun scalar(sql: String): Long =
        dataSource.connection.use { conn -> conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } } }

    private fun string(sql: String): String =
        dataSource.connection.use { conn -> conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) } } }

    private fun strings(sql: String): List<String> =
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val out = mutableListOf<String>()
                    while (rs.next()) out += rs.getString(1)
                    out
                }
            }
        }
}
