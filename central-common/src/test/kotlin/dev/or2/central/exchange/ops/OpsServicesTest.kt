package dev.or2.central.exchange.ops

import dev.or2.central.db.FlywayMigrator
import dev.or2.central.exchange.ExchangeConfig
import dev.or2.central.exchange.ExchangeEngine
import dev.or2.central.exchange.OpsConfig
import dev.or2.central.exchange.health.AlertService
import dev.or2.central.exchange.model.CreateOrderRequest
import dev.or2.central.exchange.model.CreateOrderResult
import dev.or2.central.exchange.model.FreezeScope
import dev.or2.central.exchange.model.OrderStatus
import dev.or2.central.exchange.model.RejectReason
import dev.or2.central.exchange.model.Side
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Phase 6 staff operations end to end on embedded PostgreSQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpsServicesTest {
    private lateinit var postgres: EmbeddedPostgres
    private lateinit var dataSource: DataSource
    private lateinit var engine: ExchangeEngine
    private lateinit var alerts: AlertService
    private lateinit var freezes: FreezeService
    private lateinit var reversals: ReversalService
    private lateinit var items: ItemAdminService
    private val names = AtomicInteger()
    private val nextItem = AtomicInteger(30_000)
    private val config = ExchangeConfig(ops = OpsConfig(probingFlagThreshold = 3, rateLimitFlagThreshold = 2))
    private val staff = 0

    @BeforeAll
    fun start() {
        postgres = EmbeddedPostgres.builder().start()
        dataSource = postgres.postgresDatabase
        FlywayMigrator.migrate(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "")
        engine = ExchangeEngine(dataSource, { config })
        alerts = AlertService(dataSource)
        freezes = FreezeService(dataSource, engine)
        reversals = ReversalService(dataSource)
        items = ItemAdminService(dataSource, engine, { config }, alerts)
    }

    @AfterAll
    fun stop() {
        postgres.close()
    }

    @Test
    fun globalFreezeStopsNewOrdersButNotCancelsOrClaims() {
        val item = newItem()
        val who = character()
        val sell = place(who, item, Side.SELL, 5, 100)
        assertTrue(freezes.apply(FreezeScope.GLOBAL, 0, "UPDATE", "maintenance", null))
        assertFalse(freezes.apply(FreezeScope.GLOBAL, 0, "UPDATE", "again", null))
        val rejected = engine.createOrder(CreateOrderRequest(character(), item, Side.BUY, 1, 100, "r"))
        assertIs<CreateOrderResult.Rejected>(rejected)
        assertEquals(RejectReason.TRADING_FROZEN, rejected.reason)
        assertIs<dev.or2.central.exchange.model.CancelResult.Cancelled>(engine.cancel(sell, who, dev.or2.central.exchange.model.CancelReason.PLAYER))
        assertIs<dev.or2.central.exchange.model.ClaimResult.Started>(engine.beginClaim(who, "c", item, 5, 0))
        assertTrue(freezes.lift(FreezeScope.GLOBAL, 0, null, "done"))
        assertTrue(freezes.active().none { it.scope == FreezeScope.GLOBAL })
        assertEquals(1, scalar("SELECT count(*) FROM exchange_events WHERE event_type = 'FREEZE_LIFTED' AND reason = 'done'"))
    }

    @Test
    fun accountFreezeWithoutHoldCancelsAndRefunds() {
        val item = newItem()
        val who = character()
        place(who, item, Side.BUY, 3, 100)
        assertTrue(freezes.apply(FreezeScope.ACCOUNT, who.toLong(), "INVESTIGATION", "dupe check", null, holdOrders = false))
        assertTrue(engine.liveOrders(who).none { it.status.live })
        assertEquals(300, engine.collectionBox(who).gp)
        val claim = engine.beginClaim(who, "c", null, 0, 300)
        assertIs<dev.or2.central.exchange.model.ClaimResult.Rejected>(claim)
        assertEquals(RejectReason.ACCOUNT_FROZEN, claim.reason)
        freezes.lift(FreezeScope.ACCOUNT, who.toLong(), null, "cleared")
        assertIs<dev.or2.central.exchange.model.ClaimResult.Started>(engine.beginClaim(who, "c2", null, 0, 300))
    }

    @Test
    fun reversalMovesAssetsBackAndRecordsShortfallAsDebt() {
        val item = newItem()
        val seller = character()
        val buyer = character()
        place(seller, item, Side.SELL, 10, 100)
        place(buyer, item, Side.BUY, 10, 100)
        val tradeId = scalar("SELECT max(id) FROM exchange_trades WHERE obj_id = $item")
        assertIs<dev.or2.central.exchange.model.ClaimResult.Started>(engine.beginClaim(buyer, "c", item, 4, 0))

        val dry = reversals.reverse(tradeId, staff, "dupe", dryRun = true)
        assertFalse(dry.applied)
        assertEquals(6, dry.plan.itemsAvailable)
        assertEquals(4, dry.plan.itemsShortfall)
        assertEquals(980, dry.plan.gpAvailable)
        assertEquals(null, engine.collectionBox(seller).items[item], "a dry run must not move anything")
        assertEquals(980, engine.collectionBox(seller).gp)

        val applied = reversals.reverse(tradeId, staff, "dupe", dryRun = false)
        assertTrue(applied.applied)
        assertEquals(6, engine.collectionBox(seller).items[item])
        assertEquals(0, engine.collectionBox(seller).gp)
        assertEquals(980, engine.collectionBox(buyer).gp)
        assertEquals(1, scalar("SELECT count(*) FROM exchange_debts WHERE character_id = $buyer AND obj_id = $item AND count = 4"))
        assertEquals(1, scalar("SELECT count(*) FROM exchange_trades WHERE id = $tradeId AND reversed_at IS NOT NULL"))
        assertEquals(2, scalar("SELECT count(*) FROM exchange_events WHERE event_type = 'TRADE_REVERSED' AND trade_id = $tradeId"))
        assertEquals(0, scalar("SELECT volume FROM exchange_stats_hourly WHERE obj_id = $item"))
        val again = runCatching { reversals.reverse(tradeId, staff, "twice", dryRun = false) }
        assertTrue(again.isFailure)
    }

    @Test
    fun delistCancelsOpenOrdersAndBlocksNewOnes() {
        val item = newItem()
        val who = character()
        place(who, item, Side.SELL, 2, 50)
        assertEquals(1, items.delist(item, staff, "quest item"))
        assertEquals(2, engine.collectionBox(who).items[item])
        assertEquals(OrderStatus.CANCELLED, engine.liveOrders(who).firstOrNull()?.status ?: OrderStatus.CANCELLED)
        val rejected = engine.createOrder(CreateOrderRequest(who, item, Side.SELL, 1, 50, "x"))
        assertIs<CreateOrderResult.Rejected>(rejected)
        assertEquals(RejectReason.ITEM_FROZEN, rejected.reason)
        items.relist(item, staff, "mistake")
        assertIs<CreateOrderResult.Pending>(engine.createOrder(CreateOrderRequest(who, item, Side.SELL, 1, 50, "y")))
    }

    @Test
    fun importFillsNullsAndOnlyOverwritesWhenAsked() {
        val a = nextItem.incrementAndGet()
        val b = nextItem.incrementAndGet()
        exec("INSERT INTO exchange_items (obj_id, base_price) VALUES ($b, 999)")
        val first = items.importItems("""{"$a":{"base_price":100,"buy_limit":50},"$b":{"base_price":1,"buy_limit":5},"junk":{}}""", staff, "launch prices")
        assertEquals(1, first.inserted)
        assertEquals(1, first.updated)
        assertEquals(1, first.skipped)
        assertEquals(999, scalar("SELECT base_price FROM exchange_items WHERE obj_id = $b"))
        assertEquals(5, scalar("SELECT buy_limit FROM exchange_items WHERE obj_id = $b"))
        val second = items.importItems("""{"$b":{"base_price":1}}""", staff, "correction", overwrite = true)
        assertEquals(1, second.updated)
        assertEquals(1, scalar("SELECT base_price FROM exchange_items WHERE obj_id = $b"))
        assertEquals(1, scalar("SELECT count(*) FROM exchange_events WHERE event_type = 'BASE_PRICE_SET' AND obj_id = $b"))
    }

    @Test
    fun launchLimitCapsFillsUntilTheWindowEnds() {
        val item = newItem()
        items.launch(item, staff, "new drop", launchBuyLimit = 2, hours = 1)
        assertEquals(1, scalar("SELECT count(*) FROM exchange_alerts WHERE kind = 'NEW_ITEM_WATCH' AND obj_id = $item"))
        assertEquals("DISCOVERY", string("SELECT launch_state FROM exchange_items WHERE obj_id = $item"))
        place(character(), item, Side.SELL, 10, 100)
        val buyer = character()
        val buy = place(buyer, item, Side.BUY, 5, 100)
        assertEquals(2, engine.findOrder(buy)!!.filledQuantity)
        exec("UPDATE exchange_items SET launch_limit_until = now() - interval '1 minute' WHERE obj_id = $item")
        exec("DELETE FROM exchange_buy_limit_usage WHERE obj_id = $item")
        assertEquals(3, engine.matchPass(buy).filledQuantity)
    }

    @Test
    fun rejectionsAreLedgeredAndFlaggedWhenPersistent() {
        val who = character()
        repeat(3) { engine.recordRejection(who, "INVALID_INPUT", 1, "qty=-1") }
        assertEquals(3, scalar("SELECT count(*) FROM exchange_events WHERE event_type = 'REQUEST_REJECTED' AND character_id = $who"))
        assertEquals(1, scalar("SELECT count(*) FROM exchange_flags WHERE kind = 'INVALID_INPUT_PROBING' AND character_id = $who"))
        repeat(3) { engine.recordRejection(who, "RATE_LIMITED", 1) }
        assertEquals(1, scalar("SELECT count(*) FROM exchange_flags WHERE kind = 'RATE_LIMITED' AND character_id = $who"))
    }

    @Test
    fun notificationsAreDeliveredOnce() {
        val item = newItem()
        val seller = character()
        place(seller, item, Side.SELL, 1, 100)
        place(character(), item, Side.BUY, 1, 100)
        val pending = engine.undeliveredNotifications(seller)
        assertEquals(listOf("ORDER_FILLED"), pending.map { it.kind })
        assertEquals(1, engine.markNotificationsDelivered(pending.map { it.id }, 1))
        assertTrue(engine.undeliveredNotifications(seller).isEmpty())
        val history = engine.playerHistory(seller)
        assertEquals(1, history.size)
        assertEquals(2, history.single().taxPaid)
        assertEquals(100, history.single().averagePrice)
    }

    private fun place(character: Int, item: Int, side: Side, quantity: Long, limit: Long): Long {
        val created = engine.createOrder(CreateOrderRequest(character, item, side, quantity, limit, UUID.randomUUID().toString()))
        assertIs<CreateOrderResult.Pending>(created, "create rejected: $created")
        engine.openOrder(created.order.id, character)
        engine.matchPass(created.order.id)
        return created.order.id
    }

    private fun newItem(): Int {
        val id = nextItem.incrementAndGet()
        exec("INSERT INTO exchange_items (obj_id, base_price) VALUES ($id, 100)")
        return id
    }

    private fun character(): Int {
        val name = "ops_${names.incrementAndGet()}"
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
}
