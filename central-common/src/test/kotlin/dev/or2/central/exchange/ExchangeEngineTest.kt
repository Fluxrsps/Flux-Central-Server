package dev.or2.central.exchange

import dev.or2.central.db.FlywayMigrator
import dev.or2.central.exchange.model.CancelReason
import dev.or2.central.exchange.model.CancelResult
import dev.or2.central.exchange.model.ClaimResult
import dev.or2.central.exchange.model.ClaimStatus
import dev.or2.central.exchange.model.CreateOrderRequest
import dev.or2.central.exchange.model.CreateOrderResult
import dev.or2.central.exchange.model.ExchangeOrder
import dev.or2.central.exchange.model.OpenOrderResult
import dev.or2.central.exchange.model.OrderStatus
import dev.or2.central.exchange.model.RecoveryAction
import dev.or2.central.exchange.model.RecoveryMarkers
import dev.or2.central.exchange.model.RejectReason
import dev.or2.central.exchange.model.Side
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the engine end to end against embedded PostgreSQL. Every scenario finishes by checking
 * the conservation invariant: everything that entered the exchange is still accounted for in
 * reservations, collection boxes, claims and tax.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeEngineTest {
    private lateinit var postgres: EmbeddedPostgres
    private lateinit var dataSource: DataSource
    private lateinit var engine: ExchangeEngine
    private val nextItem = AtomicInteger(10_000)
    private val nextName = AtomicInteger(0)
    private var config = ExchangeConfig.DEFAULT
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
    }

    @AfterAll
    fun stop() {
        postgres.close()
    }

    @Test
    fun basicTradeSettlesAtRestingPriceAndTaxesSeller() {
        val item = newItem()
        val seller = newCharacter()
        val buyer = newCharacter()
        val sell = place(seller, item, Side.SELL, 100, 500)
        val buy = place(buyer, item, Side.BUY, 100, 500)

        assertEquals(1, buy.fills.size)
        assertEquals(500, buy.fills[0].unitPrice)
        assertEquals(OrderStatus.FILLED, order(buy.orderId).status)
        assertEquals(OrderStatus.FILLED, order(sell.orderId).status)

        val buyerBox = engine.collectionBox(buyer)
        assertEquals(mapOf(item to 100L), buyerBox.items)
        assertEquals(0, buyerBox.gp)
        val sellerBox = engine.collectionBox(seller)
        assertEquals(50_000L - 1_000L, sellerBox.gp)
        assertConserved(item)
    }

    @Test
    fun buyerOverpayingLimitGetsSurplusReleased() {
        val item = newItem()
        val seller = newCharacter()
        val buyer = newCharacter()
        place(seller, item, Side.SELL, 1, 100)
        val buy = place(buyer, item, Side.BUY, 1, 200)
        assertEquals(100, buy.fills.single().unitPrice)
        assertEquals(100, engine.collectionBox(buyer).gp)
        assertEquals(98, engine.collectionBox(seller).gp)
        assertConserved(item)
    }

    @Test
    fun sellerUnderpricingRestingBuyGetsBuyersPrice() {
        val item = newItem()
        val seller = newCharacter()
        val buyer = newCharacter()
        place(buyer, item, Side.BUY, 1, 200)
        val sell = place(seller, item, Side.SELL, 1, 100)
        assertEquals(200, sell.fills.single().unitPrice)
        assertEquals(0, engine.collectionBox(buyer).gp)
        assertEquals(196, engine.collectionBox(seller).gp)
        assertConserved(item)
    }

    @Test
    fun partialFillLeavesRemainderOpenWithCorrectReservation() {
        val item = newItem()
        val seller = newCharacter()
        val buyer = newCharacter()
        val sell = place(seller, item, Side.SELL, 100, 300)
        place(buyer, item, Side.BUY, 40, 300)
        val remaining = order(sell.orderId)
        assertEquals(OrderStatus.PARTIALLY_FILLED, remaining.status)
        assertEquals(60, remaining.remainingQuantity)
        assertEquals(60, remaining.reservedAmount)
        assertConserved(item)
    }

    @Test
    fun manyBuyersFillOneSellOrderAsSeparateTrades() {
        val item = newItem()
        val seller = newCharacter()
        val sell = place(seller, item, Side.SELL, 900, 100)
        for (qty in listOf(100L, 250L, 300L, 250L)) {
            val buy = place(newCharacter(), item, Side.BUY, qty, 100)
            assertEquals(qty, buy.fills.single().quantity)
        }
        val done = order(sell.orderId)
        assertEquals(OrderStatus.FILLED, done.status)
        assertEquals(4, count("SELECT count(*) FROM exchange_trades WHERE sell_order_id = ${sell.orderId}"))
        assertEquals(900 * 100 - 900 * 2, engine.collectionBox(seller).gp)
        assertConserved(item)
    }

    @Test
    fun oneBuyFillsManySellersInPriceTimeOrder() {
        val item = newItem()
        val a = place(newCharacter(), item, Side.SELL, 100, 120)
        val b = place(newCharacter(), item, Side.SELL, 250, 100)
        val c = place(newCharacter(), item, Side.SELL, 300, 100)
        val d = place(newCharacter(), item, Side.SELL, 250, 110)
        val buy = place(newCharacter(), item, Side.BUY, 900, 200)
        assertEquals(listOf(b.orderId, c.orderId, d.orderId, a.orderId), buy.fills.map { it.sellOrderId })
        assertEquals(listOf(100L, 100L, 110L, 120L), buy.fills.map { it.unitPrice })
        assertEquals(OrderStatus.FILLED, order(buy.orderId).status)
        assertConserved(item)
    }

    @Test
    fun selfTradeIsPrevented() {
        val item = newItem()
        val me = newCharacter()
        place(me, item, Side.SELL, 10, 100)
        val buy = place(me, item, Side.BUY, 10, 100)
        assertTrue(buy.fills.isEmpty())
        assertEquals(OrderStatus.OPEN, order(buy.orderId).status)
        assertConserved(item)
    }

    @Test
    fun selfTradeIsPreventedAcrossAnAccountsCharacters() {
        val item = newItem()
        val me = newCharacter()
        val alt = altOf(me)

        // The alt undercuts the book, so it would be taken first were it anybody else's offer.
        val own = place(alt, item, Side.SELL, 10, 50)
        val stranger = place(newCharacter(), item, Side.SELL, 10, 100)

        val buy = place(me, item, Side.BUY, 10, 100)

        assertEquals(listOf(stranger.orderId), buy.fills.map { it.sellOrderId })
        assertEquals(listOf(100L), buy.fills.map { it.unitPrice })
        assertEquals(OrderStatus.OPEN, order(own.orderId).status)
        assertConserved(item)
    }

    @Test
    fun anotherAccountStillMatches() {
        val item = newItem()
        val me = newCharacter()
        altOf(me)
        val sell = place(newCharacter(), item, Side.SELL, 10, 100)
        val buy = place(me, item, Side.BUY, 10, 100)

        assertEquals(listOf(sell.orderId), buy.fills.map { it.sellOrderId })
        assertEquals(OrderStatus.FILLED, order(buy.orderId).status)
        assertConserved(item)
    }

    @Test
    fun noLiquidityLeavesOrderOpen() {
        val item = newItem()
        val buy = place(newCharacter(), item, Side.BUY, 5, 100)
        assertTrue(buy.fills.isEmpty())
        assertEquals(OrderStatus.OPEN, order(buy.orderId).status)
    }

    @Test
    fun boundedPassContinuesOnNextPass() {
        withConfig(config.copy(maxFillsPerTransaction = 2)) {
            val item = newItem()
            repeat(5) { place(newCharacter(), item, Side.SELL, 1, 100) }
            val buy = place(newCharacter(), item, Side.BUY, 5, 100, extraPasses = 0)
            assertEquals(2, buy.fills.size)
            assertEquals(2, engine.matchPass(buy.orderId).fills.size)
            assertEquals(1, engine.matchPass(buy.orderId).fills.size)
            assertEquals(OrderStatus.FILLED, order(buy.orderId).status)
            assertConserved(item)
        }
    }

    @Test
    fun cancelAfterPartialFillReleasesOnlyRemainder() {
        val item = newItem()
        val buyer = newCharacter()
        place(newCharacter(), item, Side.SELL, 600, 450)
        val buy = place(buyer, item, Side.BUY, 900, 500)
        assertEquals(600 * 50L, engine.collectionBox(buyer).gp)
        val result = engine.cancel(buy.orderId, buyer, CancelReason.PLAYER)
        assertIs<CancelResult.Cancelled>(result)
        assertEquals(300 * 500L, result.releasedGp)
        assertEquals(180_000L, engine.collectionBox(buyer).gp)
        assertIs<CancelResult.AlreadyTerminal>(engine.cancel(buy.orderId, buyer, CancelReason.PLAYER))
        assertConserved(item)
    }

    @Test
    fun claimIsIdempotentAndDebitsAtStart() {
        val item = newItem()
        val buyer = newCharacter()
        place(newCharacter(), item, Side.SELL, 10, 100)
        place(buyer, item, Side.BUY, 10, 100)
        val request = "claim-1"
        val started = engine.beginClaim(buyer, request, item, 4, 0)
        assertIs<ClaimResult.Started>(started)
        assertEquals(6, engine.collectionBox(buyer).items[item])
        assertIs<ClaimResult.Existing>(engine.beginClaim(buyer, request, item, 4, 0))
        assertEquals(ClaimStatus.COMPLETE, engine.completeClaim(started.claim.id)?.status)
        assertEquals(ClaimStatus.COMPLETE, engine.completeClaim(started.claim.id)?.status)

        val tooMany = engine.beginClaim(buyer, "claim-2", item, 7, 0)
        assertIs<ClaimResult.Rejected>(tooMany)
        assertEquals(RejectReason.INSUFFICIENT_COLLECTION, tooMany.reason)

        val released = engine.beginClaim(buyer, "claim-3", item, 6, 0)
        assertIs<ClaimResult.Started>(released)
        assertTrue(engine.collectionBox(buyer).items.isEmpty())
        engine.releaseClaim(released.claim.id)
        assertEquals(6, engine.collectionBox(buyer).items[item])
        assertConserved(item)
    }

    @Test
    fun buyLimitStopsFillsUntilWindowFrees() {
        val item = newItem(buyLimit = 5)
        val buyer = newCharacter()
        place(newCharacter(), item, Side.SELL, 20, 100)
        val buy = place(buyer, item, Side.BUY, 8, 100)
        assertEquals(5, buy.filledQuantity)
        assertTrue(engine.matchPass(buy.orderId).fills.isEmpty())
        now = now.plus(Duration.ofHours(5))
        assertEquals(3, engine.matchPass(buy.orderId).filledQuantity)
        assertEquals(OrderStatus.FILLED, order(buy.orderId).status)
        assertConserved(item)
    }

    @Test
    fun buyLimitIsPerBuyer() {
        val item = newItem(buyLimit = 3)
        place(newCharacter(), item, Side.SELL, 9, 100)
        repeat(3) { assertEquals(3, place(newCharacter(), item, Side.BUY, 3, 100).filledQuantity) }
        assertConserved(item)
    }

    @Test
    fun validationRejectsBadInput() {
        val item = newItem()
        val who = newCharacter()
        assertRejected(RejectReason.INVALID_INPUT, engine.createOrder(request(who, item, Side.BUY, 0, 100)))
        assertRejected(RejectReason.INVALID_INPUT, engine.createOrder(request(who, item, Side.BUY, 1, 0)))
        assertRejected(RejectReason.MAX_QUANTITY, engine.createOrder(request(who, item, Side.BUY, Long.MAX_VALUE / 2, 3)))
        assertRejected(RejectReason.MAX_VALUE, engine.createOrder(request(who, item, Side.BUY, 2_000_000_000, 1_000_000_000)))
        assertRejected(RejectReason.UNKNOWN_ITEM, engine.createOrder(request(who, 1, Side.BUY, 1, 1)))
        withConfig(config.copy(maxActiveOrders = 2)) {
            place(who, item, Side.BUY, 1, 1)
            place(who, item, Side.BUY, 1, 1)
            assertRejected(RejectReason.SLOT_LIMIT, engine.createOrder(request(who, item, Side.BUY, 1, 1)))
        }
    }

    @Test
    fun duplicateRequestReturnsOriginal() {
        val item = newItem()
        val who = newCharacter()
        val req = request(who, item, Side.BUY, 1, 100)
        val first = engine.createOrder(req)
        assertIs<CreateOrderResult.Pending>(first)
        val second = engine.createOrder(req)
        assertIs<CreateOrderResult.Existing>(second)
        assertEquals(first.order.id, second.order.id)
    }

    @Test
    fun concurrentBuyersOnlyOneGetsTheLastHundred() {
        val item = newItem()
        place(newCharacter(), item, Side.SELL, 100, 100)
        val a = newCharacter()
        val b = newCharacter()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val fa = pool.submit<Placed> { place(a, item, Side.BUY, 100, 100) }
            val fb = pool.submit<Placed> { place(b, item, Side.BUY, 100, 100) }
            val filled = listOf(fa.get(), fb.get()).map { it.filledQuantity }
            assertEquals(listOf(0L, 100L), filled.sorted())
        } finally {
            pool.shutdown()
        }
        assertConserved(item)
    }

    @Test
    fun concurrentCancelAndFillNeverBothApply() {
        val seller = newCharacter()
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(20) {
                val item = newItem()
                val sell = place(seller, item, Side.SELL, 10, 100)
                val buyer = newCharacter()
                val buy = pending(buyer, item, Side.BUY, 10, 100)
                val fill = pool.submit<Long> { openAndMatch(buy, buyer).filledQuantity }
                val cancel = pool.submit<CancelResult> { engine.cancel(sell.orderId, seller, CancelReason.PLAYER) }
                val filled = fill.get()
                val cancelled = cancel.get()
                val released = (cancelled as? CancelResult.Cancelled)?.releasedItems ?: 0
                assertEquals(10, filled + released, "fill and cancel overlapped on order ${sell.orderId}")
                assertConserved(item)
            }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun expiryUsesCancellationPath() {
        val item = newItem()
        val who = newCharacter()
        val pending = engine.createOrder(request(who, item, Side.SELL, 5, 100).copy(expiresAt = now.plusSeconds(60)))
        assertIs<CreateOrderResult.Pending>(pending)
        engine.openOrder(pending.order.id, who)
        assertEquals(0, engine.expireDue())
        now = now.plusSeconds(120)
        assertEquals(1, engine.expireDue())
        assertEquals(OrderStatus.EXPIRED, order(pending.order.id).status)
        assertEquals(5, engine.collectionBox(who).items[item])
        assertConserved(item)
    }

    @Test
    fun sweeperMatchesBooksThatCrossedLater() {
        val item = newItem(buyLimit = 1)
        val buyer = newCharacter()
        place(newCharacter(), item, Side.SELL, 2, 100)
        val buy = place(buyer, item, Side.BUY, 2, 100)
        assertEquals(1, buy.filledQuantity)
        assertEquals(0, engine.sweep())
        now = now.plus(Duration.ofHours(5))
        assertEquals(1, engine.sweep())
        assertEquals(OrderStatus.FILLED, order(buy.orderId).status)
        assertConserved(item)
    }

    @Test
    fun recoveryRefundsOnlyWhenTheSaveHasTheMarker() {
        val item = newItem()
        val who = newCharacter()
        val withMarker = pending(who, item, Side.BUY, 3, 100)
        val withoutMarker = pending(who, item, Side.SELL, 4, 100)
        val claimed = engine.beginClaimForTest(who, item)
        val unclaimed = engine.beginClaimForTest(who, item)
        val gpBefore = engine.collectionBox(who).gp
        now = now.plusSeconds(config.recoveryGraceSeconds + 1)
        val late = pending(who, item, Side.BUY, 1, 100)

        val actions = engine.recover(who, RecoveryMarkers(setOf(withMarker), setOf(claimed.id)))
        assertEquals(
            setOf(
                RecoveryAction.OrderRefunded(withMarker),
                RecoveryAction.OrderDropped(withoutMarker),
                RecoveryAction.ClaimCompleted(claimed.id),
                RecoveryAction.ClaimReleased(unclaimed.id),
            ),
            actions.toSet(),
        )
        assertEquals(gpBefore + 300, engine.collectionBox(who).gp)
        assertEquals(1, engine.collectionBox(who).items[item])
        assertEquals(OrderStatus.PENDING, order(late).status)
        assertEquals(OrderStatus.CANCELLED, order(withoutMarker).status)
        assertEquals(CancelReason.SYSTEM_FAILURE, order(withoutMarker).cancelReason)
        assertTrue(engine.recover(who, RecoveryMarkers(emptySet(), emptySet())).isEmpty())
    }

    @Test
    fun frozenItemNeitherAcceptsNorMatches() {
        val item = newItem()
        val sell = place(newCharacter(), item, Side.SELL, 1, 100)
        exec("INSERT INTO exchange_freezes (scope, target_id, reason_code, reason) VALUES ('ITEM', $item, 'TEST', 'test')")
        assertRejected(RejectReason.ITEM_FROZEN, engine.createOrder(request(newCharacter(), item, Side.BUY, 1, 100)))
        assertTrue(engine.matchPass(sell.orderId).fills.isEmpty())
        exec("UPDATE exchange_freezes SET lifted_at = now() WHERE target_id = $item")
        assertIs<CancelResult.Cancelled>(engine.cancel(sell.orderId, null, CancelReason.FROZEN))
    }

    private data class Placed(val orderId: Long, val fills: List<dev.or2.central.exchange.model.Fill>) {
        val filledQuantity: Long
            get() = fills.sumOf { it.quantity }
    }

    private fun request(character: Int, item: Int, side: Side, quantity: Long, limit: Long) =
        CreateOrderRequest(character, item, side, quantity, limit, UUID.randomUUID().toString())

    private fun pending(character: Int, item: Int, side: Side, quantity: Long, limit: Long): Long {
        val created = engine.createOrder(request(character, item, side, quantity, limit))
        assertIs<CreateOrderResult.Pending>(created, "create rejected: $created")
        return created.order.id
    }

    private fun openAndMatch(orderId: Long, character: Int, extraPasses: Int = 20): Placed {
        assertIs<OpenOrderResult.Opened>(engine.openOrder(orderId, character))
        val fills = mutableListOf<dev.or2.central.exchange.model.Fill>()
        var pass = engine.matchPass(orderId)
        fills += pass.fills
        var passes = 0
        while (pass.fills.isNotEmpty() && pass.order?.status?.live == true && passes++ < extraPasses) {
            pass = engine.matchPass(orderId)
            fills += pass.fills
        }
        return Placed(orderId, fills)
    }

    private fun place(character: Int, item: Int, side: Side, quantity: Long, limit: Long, extraPasses: Int = 20): Placed =
        openAndMatch(pending(character, item, side, quantity, limit), character, extraPasses)

    private fun ExchangeEngine.beginClaimForTest(character: Int, item: Int): dev.or2.central.exchange.model.ExchangeClaim {
        place(newCharacter(), item, Side.SELL, 1, 100)
        place(character, item, Side.BUY, 1, 100)
        val result = beginClaim(character, UUID.randomUUID().toString(), item, 1, 0)
        assertIs<ClaimResult.Started>(result)
        return result.claim
    }

    private fun order(id: Long): ExchangeOrder = assertNotNull(engine.findOrder(id))

    private fun assertRejected(reason: RejectReason, result: CreateOrderResult) {
        assertIs<CreateOrderResult.Rejected>(result)
        assertEquals(reason, result.reason)
    }

    private fun withConfig(cfg: ExchangeConfig, block: () -> Unit) {
        val previous = config
        config = cfg
        try {
            block()
        } finally {
            config = previous
        }
    }

    /**
     * For one item: every unit reserved by a SELL that has been consumed must now be in a
     * collection box, on a pending claim, or claimed; every GP a BUY consumed must be seller
     * proceeds plus tax. Orders that were refunded without a reservation (recovery) are excluded
     * because their assets never entered the exchange.
     */
    private fun assertConserved(item: Int) {
        val itemsIn = count("SELECT COALESCE(sum(filled_quantity), 0) FROM exchange_orders WHERE obj_id = $item AND side = 'SELL'")
        val itemsToBuyers = count("SELECT COALESCE(sum(quantity), 0) FROM exchange_trades WHERE obj_id = $item")
        assertEquals(itemsIn, itemsToBuyers, "items filled on SELL orders must equal items traded")

        val gpPaid = count("SELECT COALESCE(sum(gross_value), 0) FROM exchange_trades WHERE obj_id = $item")
        val gpToSellers = count("SELECT COALESCE(sum(net_value), 0) FROM exchange_trades WHERE obj_id = $item")
        val tax = count("SELECT COALESCE(sum(tax), 0) FROM exchange_trades WHERE obj_id = $item")
        assertEquals(gpPaid, gpToSellers + tax, "buyer paid must equal seller net plus tax")

        val badReservations =
            count(
                """
                SELECT count(*) FROM exchange_orders
                WHERE obj_id = $item AND status IN ('OPEN', 'PARTIALLY_FILLED')
                  AND reserved_amount <> CASE side WHEN 'BUY' THEN (quantity - filled_quantity) * limit_price ELSE quantity - filled_quantity END
                """.trimIndent(),
            )
        assertEquals(0, badReservations)
    }

    private fun newItem(buyLimit: Int? = null): Int {
        val id = nextItem.incrementAndGet()
        exec("INSERT INTO exchange_items (obj_id, base_price, buy_limit) VALUES ($id, 100, ${buyLimit ?: "NULL"})")
        return id
    }

    private fun newCharacter(): Int {
        val name = "engine_${nextName.incrementAndGet()}"
        exec("INSERT INTO accounts (account_name, password_hash) VALUES ('$name', 'x')")
        exec("INSERT INTO account_characters (account_id, display_name) SELECT id, '$name' FROM accounts WHERE account_name = '$name'")
        return count("SELECT id FROM account_characters WHERE display_name = '$name'").toInt()
    }

    /** Another character on the same account as [characterId] - the alt a wash trade would use. */
    private fun altOf(characterId: Int): Int {
        val name = "engine_alt_${nextName.incrementAndGet()}"
        exec(
            "INSERT INTO account_characters (account_id, display_name) " +
                "SELECT account_id, '$name' FROM account_characters WHERE id = $characterId",
        )
        return count("SELECT id FROM account_characters WHERE display_name = '$name'").toInt()
    }

    private fun exec(sql: String) {
        dataSource.connection.use { it.createStatement().use { st -> st.executeUpdate(sql) } }
    }

    private fun count(sql: String): Long =
        dataSource.connection.use { conn ->
            conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
        }
}
