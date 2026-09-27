package dev.or2.central.exchange.health

import dev.or2.central.db.FlywayMigrator
import dev.or2.central.exchange.ExchangeConfig
import dev.or2.central.exchange.ExchangeEngine
import dev.or2.central.exchange.HealthConfig
import dev.or2.central.exchange.LiquidityConfig
import dev.or2.central.exchange.model.ClaimResult
import dev.or2.central.exchange.model.CreateOrderRequest
import dev.or2.central.exchange.model.CreateOrderResult
import dev.or2.central.exchange.model.OrderStatus
import dev.or2.central.exchange.model.Side
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Phase 5 services end to end on embedded PostgreSQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthServicesTest {
    private lateinit var postgres: EmbeddedPostgres
    private lateinit var dataSource: DataSource
    private lateinit var engine: ExchangeEngine
    private lateinit var alerts: AlertService
    private val notified = mutableListOf<ExchangeAlert>()
    private val names = AtomicInteger()
    private val items = AtomicInteger(20_000)
    private var config = ExchangeConfig()
    private var now: Instant = Instant.now()
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant() = now
    }

    @BeforeAll
    fun start() {
        postgres = EmbeddedPostgres.builder().start()
        dataSource = postgres.postgresDatabase
        FlywayMigrator.migrate(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "")
        engine = ExchangeEngine(dataSource, { config }, clock = clock)
        alerts = AlertService(dataSource, { notified += it }, clock)
    }

    @AfterAll
    fun stop() {
        postgres.close()
    }

    @Test
    fun alertsDeduplicateUntilResolved() {
        val before = notified.size
        assertTrue(alerts.raise("PRICE_CHANGE_24H", 4151, AlertSeverity.WARN, """{"a":1}"""))
        assertFalse(alerts.raise("PRICE_CHANGE_24H", 4151, AlertSeverity.WARN, """{"a":2}"""))
        assertEquals(before + 1, notified.size)
        val open = alerts.listOpen().first { it.kind == "PRICE_CHANGE_24H" && it.objId == 4151 }
        assertTrue(open.details.contains("\"a\": 2") || open.details.contains("\"a\":2"))
        assertTrue(alerts.resolve(open.id, null))
        assertTrue(alerts.raise("PRICE_CHANGE_24H", 4151, AlertSeverity.WARN, """{"a":3}"""))
    }

    @Test
    fun reconciliationIsCleanAfterTradingAndCatchesTampering() {
        val item = newItem()
        val recon = ReconciliationService(dataSource, alerts, clock)
        val seller = character()
        val buyer = character()
        place(seller, item, Side.SELL, 50, 100)
        place(buyer, item, Side.BUY, 30, 120)
        engine.cancel(engine.liveOrders(seller).first().id, seller, dev.or2.central.exchange.model.CancelReason.PLAYER)
        engine.beginClaim(buyer, "c1", item, 10, 0).let { assertIs<ClaimResult.Started>(it); engine.completeClaim(it.claim.id) }
        assertTrue(recon.run().clean)

        exec("UPDATE exchange_collection_items SET count = count + 5 WHERE character_id = $buyer AND obj_id = $item")
        val report = recon.run()
        assertEquals(listOf("ITEMS_CUSTODY"), report.mismatches.map { it.check })
        assertEquals(1, scalar("SELECT count(*) FROM exchange_freezes WHERE scope = 'ITEM' AND target_id = $item AND lifted_at IS NULL"))
        assertEquals(1, scalar("SELECT count(*) FROM exchange_alerts WHERE kind = 'RECONCILIATION' AND obj_id = $item AND status = 'OPEN'"))
        exec("UPDATE exchange_collection_items SET count = count - 5 WHERE character_id = $buyer AND obj_id = $item")
        exec("UPDATE exchange_freezes SET lifted_at = now() WHERE target_id = $item")
        assertTrue(recon.run().clean)
    }

    @Test
    fun detectionFlagsFarFromMarketPairsAndCornering() {
        val item = newItem()
        exec("INSERT INTO exchange_market_prices (obj_id, price, stable_price, confidence) VALUES ($item, 100, 100, 0.5)")
        val a = character()
        val b = character()
        place(a, item, Side.SELL, 1, 5000)
        place(b, item, Side.BUY, 1, 5000)
        val whale = character()
        place(whale, item, Side.BUY, 1000, 100_000)
        val detection = DetectionService(dataSource, { config.copy(health = HealthConfig(corneringMinValue = 1_000_000)) }, alerts, clock)
        val first = detection.run()
        assertEquals(1, first.farFromMarket)
        assertEquals(1, first.cornering)
        val second = detection.run()
        assertEquals(0, second.total)
        assertEquals(1, scalar("SELECT count(*) FROM exchange_flags WHERE kind = 'PRICE_FAR_FROM_MARKET' AND obj_id = $item"))
        assertEquals(1, scalar("SELECT count(*) FROM exchange_flags WHERE kind = 'CORNERING' AND character_id = $whale"))
        assertEquals(1, scalar("SELECT count(*) FROM exchange_alerts WHERE kind = 'SUSPICIOUS_PATTERN' AND obj_id = $item AND status = 'OPEN'"))
    }

    @Test
    fun detectionFlagsRepeatedPairingAndRapidCancel() {
        val item = newItem()
        val a = character()
        val b = character()
        repeat(10) {
            place(a, item, Side.SELL, 1, 100)
            place(b, item, Side.BUY, 1, 100)
        }
        val churner = character()
        repeat(20) {
            val id = pending(churner, item, Side.BUY, 1, 1)
            engine.openOrder(id, churner)
            engine.cancel(id, churner, dev.or2.central.exchange.model.CancelReason.PLAYER)
        }
        val detection = DetectionService(dataSource, { config }, alerts, clock)
        val s = detection.run()
        assertEquals(1, s.repeatedPairing)
        assertEquals(1, s.rapidCancel)
    }

    @Test
    fun rollupBuildsDailyBucketsFromHourly() {
        val item = newItem()
        val seller = character()
        place(seller, item, Side.SELL, 20, 100)
        place(character(), item, Side.BUY, 10, 100)
        place(character(), item, Side.BUY, 10, 100)
        val rollup = StatsRollupService(dataSource)
        assertTrue(rollup.run() >= 1)
        val day = LocalDate.ofInstant(now, ZoneOffset.UTC)
        assertEquals(20, scalar("SELECT volume FROM exchange_stats_daily WHERE obj_id = $item AND day = '$day'"))
        assertEquals(2, scalar("SELECT distinct_buyers FROM exchange_stats_daily WHERE obj_id = $item AND day = '$day'"))
        assertEquals(1, scalar("SELECT distinct_sellers FROM exchange_stats_daily WHERE obj_id = $item AND day = '$day'"))
        assertEquals(0, rollup.run())
    }

    @Test
    fun snapshotWritesTheDay() {
        val snapshots = EconomySnapshotService(dataSource, clock)
        val day = snapshots.snapshot()
        assertEquals(1, scalar("SELECT count(*) FROM exchange_economy_snapshots WHERE day = '$day'"))
        snapshots.snapshot()
        assertEquals(1, scalar("SELECT count(*) FROM exchange_economy_snapshots WHERE day = '$day'"))
    }

    @Test
    fun systemLiquidityPlacesGuardedOrdersAndEnforcesCaps() {
        val item = newItem()
        val system = character()
        exec("UPDATE exchange_items SET high_alch_value = 60, shop_sell_value = 0 WHERE obj_id = $item")
        exec("INSERT INTO exchange_market_prices (obj_id, price, stable_price, confidence) VALUES ($item, 100, 100, 0.5)")
        exec(
            "INSERT INTO exchange_system_liquidity (obj_id, enabled, buy_spread_bps, sell_spread_bps, hourly_cap_buy, daily_cap_buy, hourly_cap_sell, daily_cap_sell, per_account_daily_cap) " +
                "VALUES ($item, TRUE, 1500, 1500, 100, 1000, 100, 1000, 30)",
        )
        val liquidityConfig = config.copy(liquidity = LiquidityConfig(enabled = true, systemCharacterId = system))
        val liquidEngine = ExchangeEngine(dataSource, { liquidityConfig }, clock = clock)
        val service = SystemLiquidityService(dataSource, liquidEngine, { liquidityConfig }, alerts, clock)

        val s = service.run()
        assertEquals(1, s.items)
        assertEquals(1, s.placed, "buy at 85 would be above the 60 alch value, so only the sell should be placed")
        val sell = liquidEngine.liveOrders(system).single()
        assertEquals(Side.SELL, sell.side)
        assertEquals(115, sell.limitPrice)
        assertEquals(100, sell.quantity)

        exec("UPDATE exchange_items SET high_alch_value = 90 WHERE obj_id = $item")
        assertEquals(2, service.run().placed, "buy at 85 is now below the 90 alch value; the sell is kept, not duplicated")
        assertEquals(2, liquidEngine.liveOrders(system).size)
        service.run()
        assertEquals(2, liquidEngine.liveOrders(system).size, "same hour rerun must not stack orders")

        val buyer = character()
        val order = pending(buyer, item, Side.BUY, 50, 115)
        liquidEngine.openOrder(order, buyer)
        val filled = liquidEngine.matchPass(order).filledQuantity
        assertEquals(30, filled, "per-account daily cap against the system is 30")
        assertEquals(OrderStatus.PARTIALLY_FILLED, liquidEngine.findOrder(order)!!.status)
    }

    private fun pending(character: Int, item: Int, side: Side, quantity: Long, limit: Long): Long {
        val created = engine.createOrder(CreateOrderRequest(character, item, side, quantity, limit, UUID.randomUUID().toString()))
        assertIs<CreateOrderResult.Pending>(created, "create rejected: $created")
        return created.order.id
    }

    private fun place(character: Int, item: Int, side: Side, quantity: Long, limit: Long) {
        val id = pending(character, item, side, quantity, limit)
        engine.openOrder(id, character)
        engine.matchPass(id)
    }

    private fun newItem(): Int {
        val id = items.incrementAndGet()
        exec("INSERT INTO exchange_items (obj_id, base_price) VALUES ($id, 100)")
        return id
    }

    private fun character(): Int {
        val name = "health_${names.incrementAndGet()}"
        exec("INSERT INTO accounts (account_name, password_hash) VALUES ('$name', 'x')")
        exec("INSERT INTO account_characters (account_id, display_name) SELECT id, '$name' FROM accounts WHERE account_name = '$name'")
        return scalar("SELECT id FROM account_characters WHERE display_name = '$name'").toInt()
    }

    private fun exec(sql: String) {
        dataSource.connection.use { it.createStatement().use { st -> st.executeUpdate(sql) } }
    }

    private fun scalar(sql: String): Long =
        dataSource.connection.use { conn ->
            conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
        }
}
